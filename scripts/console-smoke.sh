#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# scripts/console-smoke.sh — end-to-end smoke of the backend console stack over HTTP, through the
# console's nginx on :8083 exactly as the browser sees it (/console/ for the SPA, /api/... for the
# backend), push stream included. See docs/CONSOLE.md, "Smoke test".
#
# Usage:  scripts/console-smoke.sh            # boot an isolated stack (project whsmoke), test, tear down
#         scripts/console-smoke.sh --keep     # leave the stack up afterwards
#         scripts/console-smoke.sh --no-up    # test a stack that is already up (never torn down);
#                                             # it must be seeded (SEED_DEMO=true) and use the demo
#                                             # SUPERADMIN password, see docs/demo-credentials.md
# Env:    SMOKE_PROJECT (whsmoke), SMOKE_BASE (http://localhost:8083), SMOKE_ADMIN_EMAIL,
#         SMOKE_PASSWORD (Demo1234!), SMOKE_JWT_SECRET (the dev compose secret),
#         SMOKE_EXTRA_COMPOSE (one more -f file, CI uses scripts/console-smoke.ci.yml),
#         SMOKE_BOOT_TIMEOUT (900 s).
# Needs:  docker (compose v2.24+), curl, jq, python3. Exit 0 only when every assertion passed.
# The helpers above main() are exercised without Docker by scripts/console-smoke.test.sh.
# ---------------------------------------------------------------------------
set -uo pipefail

# ─── Helpers (pure, unit-tested) ────────────────────────────────────────────

# Server-Sent Events parsing over a timestamped capture: each input line is "<elapsed>\t<raw line>"
# (written by the stamp mode). Output rows are "<elapsed>\t<kind>\t<data>", kind = the event name
# (default "message"), "comment" for ": ..." lines, "exit" for the end of the capture. A frame
# without its terminating blank line is dropped, as the SSE spec says.
SMOKE_PY=$(cat <<'PY'
import hashlib, hmac, base64, json, signal, subprocess, sys, time

def b64(raw):
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()

def parse(path):
    event, data, start = None, [], None
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            ts, _, body = line.rstrip("\n").partition("\t")
            if body.startswith("#exit "):
                print("%s\texit\t%s" % (ts, body[6:]))
                event, data, start = None, [], None
                continue
            if body == "":
                if data or event:
                    print("%s\t%s\t%s" % (start, event or "message", "\\n".join(data)))
                event, data, start = None, [], None
                continue
            if body.startswith(":"):
                print("%s\tcomment\t%s" % (ts, body[1:].strip()))
                continue
            field, _, value = body.partition(":")
            if value.startswith(" "):
                value = value[1:]
            if start is None:
                start = ts
            if field == "event":
                event = value
            elif field == "data":
                data.append(value)

def find(path, kind, sid="", after="-1"):
    # First parsed row of that kind (and subscription id, when given) after `after` seconds.
    with open(path, encoding="utf-8") as f:
        for row in f:
            ts, k, data = row.rstrip("\n").split("\t", 2)
            if k != kind or float(ts) <= float(after):
                continue
            if sid:
                try:
                    if json.loads(data).get("id") != sid:
                        continue
                except ValueError:
                    continue
            print("%s\t%s" % (ts, data))
            return

def mint(secret, payload):
    head = b64(b'{"alg":"HS256","typ":"JWT"}') + "." + b64(payload.encode())
    sig = hmac.new(secret.encode(), head.encode(), hashlib.sha256).digest()
    print(head + "." + b64(sig))

def payload(token):
    part = token.split(".")[1]
    print(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)).decode())

def stamp(out, cmd):
    # Runs cmd, writing each stdout line with the seconds elapsed since the start; SIGTERM stops it.
    t0 = time.time()
    with open(out + ".t0", "w") as f:
        f.write("%.3f\n" % t0)
    proc = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    signal.signal(signal.SIGTERM, lambda *_: proc.terminate())
    with open(out, "w") as f:
        for raw in iter(proc.stdout.readline, b""):
            f.write("%.3f\t%s\n" % (time.time() - t0, raw.decode("utf-8", "replace").rstrip("\r\n")))
            f.flush()
        f.write("%.3f\t#exit %d\n" % (time.time() - t0, proc.wait()))

mode, args = sys.argv[1], sys.argv[2:]
if mode == "parse": parse(args[0])
elif mode == "find": find(*args)
elif mode == "mint": mint(*args)
elif mode == "payload": payload(*args)
elif mode == "stamp": stamp(args[0], args[1:])
elif mode == "now": print("%.3f" % time.time())
PY
)
py() { python3 -c "$SMOKE_PY" "$@"; }

sse_parse() { py parse "$1"; }                     # RAW -> rows
sse_find() { py find "$1" "$2" "${3:-}" "${4:--1}"; }  # PARSED KIND [ID] [AFTER] -> "elapsed\tdata"
jwt_mint() { py mint "$1" "$2"; }                  # SECRET PAYLOAD_JSON -> HS256 token
jwt_payload() { py payload "$1"; }                 # TOKEN -> payload JSON
now() { py now; }

# Values of one header in the LAST response of a curl -D dump (redirects and 1xx come first),
# one per line, name matched case-insensitively.
header_get() {
	awk -v want="$(printf '%s' "$2" | tr '[:upper:]' '[:lower:]')" '
		{ sub(/\r$/, "") }
		/^HTTP\// { n = 0; next }
		{ i = index($0, ":"); if (i == 0) next
		  if (tolower(substr($0, 1, i - 1)) == want) { v = substr($0, i + 1); sub(/^[ \t]+/, "", v); vals[++n] = v } }
		END { for (j = 1; j <= n; j++) print vals[j] }' "$1"
}

# Status code of the last response in a curl -D dump.
http_status() { awk '/^HTTP\// { s = $2 } END { print s }' "$1"; }

# Prints the CSP directives the console requires that are missing from $1, or unsafe ones present.
csp_problems() {
	local csp=$1 d out=""
	for d in "default-src 'self'" "object-src 'none'" "base-uri 'self'" "frame-ancestors 'none'"; do
		case "; $csp;" in *"; $d;"* | *"; $d ;"*) ;; *) out="$out missing($d)" ;; esac
	done
	case $csp in *unsafe-eval*) out="$out unsafe-eval" ;; esac
	case $csp in *"script-src"*"'unsafe-inline'"*) out="$out script-unsafe-inline" ;; esac
	printf '%s' "${out# }"
}

# Security headers every console response carries; prints what is wrong (empty = fine).
security_header_problems() {
	local h=$1 out="" csp
	csp=$(header_get "$h" Content-Security-Policy)
	if [ -z "$csp" ]; then out="no CSP"
	elif [ "$(printf '%s\n' "$csp" | wc -l | tr -d ' ')" != 1 ]; then out="several CSP headers"
	else
		local p; p=$(csp_problems "$csp"); [ -n "$p" ] && out="CSP $p"
	fi
	[ "$(header_get "$h" X-Content-Type-Options)" = nosniff ] || out="$out nosniff"
	[ "$(header_get "$h" X-Frame-Options)" = DENY ] || out="$out X-Frame-Options"
	[ -n "$(header_get "$h" Referrer-Policy)" ] || out="$out Referrer-Policy"
	printf '%s' "${out# }"
}

# Names of the needles (label=value pairs, value never printed) found in a file, space separated.
leaks() {
	local file=$1 pair found=""; shift
	for pair in "$@"; do
		[ -n "${pair#*=}" ] && grep -qF -- "${pair#*=}" "$file" && found="$found ${pair%%=*}"
	done
	printf '%s' "${found# }"
}

# Compose service names ("svc-1  | ...") of the log lines containing a needle.
leak_services() { grep -F -- "$2" "$1" | sed -E 's/^([A-Za-z0-9_.-]+)-[0-9]+ +\|.*/\1/' | sort -u | tr '\n' ' ' | sed 's/ $//'; }

# Results: record STATUS NAME DETAIL (PASS|FAIL|SKIP); summary prints the table, returns 1 on a FAIL.
RESULTS=${RESULTS:-}
record() {
	RESULTS="$RESULTS$1	$2	$3
"
	if [ "$1" = FAIL ]; then printf '  FAIL %s: %s\n' "$2" "$3"; else printf '  %s %s %s\n' "$1" "$2" "${3:+($3)}"; fi
}
check() { if [ "$3" = "$4" ]; then record PASS "$1" "$2"; else record FAIL "$1" "$2: expected '$4', got '$3'"; fi; }
summary() {
	local pass fail skip
	pass=$(printf '%s' "$RESULTS" | grep -c '^PASS')
	fail=$(printf '%s' "$RESULTS" | grep -c '^FAIL')
	skip=$(printf '%s' "$RESULTS" | grep -c '^SKIP')
	printf '\n| Result | Assertion | Detail |\n|---|---|---|\n'
	printf '%s' "$RESULTS" | awk -F'\t' 'NF { printf "| %s | %s | %s |\n", $1, $2, $3 }'
	printf '\n%s passed, %s failed, %s skipped\n' "$pass" "$fail" "$skip"
	[ "$fail" = 0 ]
}

# ─── Stack and HTTP plumbing ────────────────────────────────────────────────

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PROJECT=${SMOKE_PROJECT:-whsmoke}
BASE=${SMOKE_BASE:-http://localhost:8083}
DEVTOOLS_HOST=${SMOKE_DEVTOOLS_HOST:-localhost}
ADMIN=${SMOKE_ADMIN_EMAIL:-admin@smartwarehouse.local}
PASSWORD=${SMOKE_PASSWORD:-Demo1234!}
# The dev compose files' JWT secret; the smoke mints short-lived and per-test tokens with it.
JWT_SECRET=${SMOKE_JWT_SECRET:-dev-secret-change-in-prod-min-32-chars}
OTHER_ROLES="system@smartwarehouse.local=ADMIN_SYSTEM warehouse@smartwarehouse.local=ADMIN_WAREHOUSE operator1@smartwarehouse.local=OPERATOR"

dc() {
	local f=(-f "$ROOT/docker-compose.yml" -f "$ROOT/scripts/console-smoke.compose.yml")
	[ -n "${SMOKE_EXTRA_COMPOSE:-}" ] && f+=(-f "$ROOT/$SMOKE_EXTRA_COMPOSE")
	docker compose -p "$PROJECT" --project-directory "$ROOT" "${f[@]}" --profile infra "$@"
}

# hc OUT METHOD PATH [curl args...] -> status code; body in OUT, headers in OUT.h. Never hangs.
hc() {
	local out=$1 method=$2 path=$3; shift 3
	curl -sS -o "$out" -D "$out.h" -w '%{http_code}' --connect-timeout 5 --max-time "${HC_TIMEOUT:-20}" \
		-X "$method" "$BASE$path" "$@" 2>>"$W/curl.err" || true
}
auth() { printf 'Authorization: Bearer %s' "$1"; }
json() { printf 'Content-Type: application/json'; }

login() { # EMAIL PASSWORD -> token (empty on failure)
	local c
	c=$(hc "$W/login" POST /api/auth/login -H "$(json)" -d "$(jq -nc --arg e "$1" --arg p "$2" '{email:$e,password:$p}')")
	[ "$c" = 200 ] && jq -r '.token // empty' "$W/login"
}

# until_ok SECONDS CMD... : retries CMD every 2 s until it succeeds or the time is up.
until_ok() {
	local deadline=$(($(date +%s) + $1)); shift
	while :; do
		"$@" && return 0
		[ "$(date +%s)" -ge "$deadline" ] && return 1
		sleep 2
	done
}

# stream_open NAME TOKEN BODY MAX_SECONDS: POST /api/console/stream in the background through nginx,
# timestamping every line into $W/NAME.raw (curl -N: no client-side buffering either).
stream_open() {
	python3 -c "$SMOKE_PY" stamp "$W/$1.raw" curl -sS -N --connect-timeout 5 --max-time "$4" -D "$W/$1.h" \
		-X POST "$BASE/api/console/stream" -H "$(auth "$2")" -H "$(json)" -d "$3" 2>>"$W/curl.err" &
	echo $! >"$W/$1.pid"
	STREAM_PIDS="${STREAM_PIDS:-} $!"
}
stream_stop() { [ -f "$W/$1.pid" ] && kill "$(cat "$W/$1.pid")" 2>/dev/null; return 0; }
# stream_wait NAME KIND ID TIMEOUT [AFTER] -> "elapsed\tdata" of the first match, polled every 0.5 s.
stream_wait() {
	local deadline=$(($(date +%s) + $4)) hit
	while :; do
		if [ -f "$W/$1.raw" ]; then
			sse_parse "$W/$1.raw" >"$W/$1.sse"
			hit=$(sse_find "$W/$1.sse" "$2" "$3" "${5:--1}")
			[ -n "$hit" ] && { printf '%s' "$hit"; return 0; }
			# The capture ended: nothing more will arrive.
			[ -n "$(sse_find "$W/$1.sse" exit)" ] && return 1
		fi
		[ "$(date +%s)" -ge "$deadline" ] && return 1
		sleep 0.5
	done
}
stream_elapsed() { py now | awk -v t0="$(cat "$W/$1.raw.t0")" '{ printf "%.3f", $1 - t0 }'; }
lt() { awk -v a="$1" -v b="$2" 'BEGIN { exit !(a < b) }'; }

cleanup() {
	local p
	for p in ${STREAM_PIDS:-}; do kill "$p" 2>/dev/null; done
	if [ "$UP" = 1 ] && [ "$KEEP" = 0 ]; then
		echo "== tearing down (project $PROJECT) =="
		dc --profile dev down -v --remove-orphans >/dev/null 2>&1
	fi
	[ -n "${W:-}" ] && [ -z "${SMOKE_WORKDIR:-}" ] && rm -rf "$W"
}

# ─── Assertions ─────────────────────────────────────────────────────────────

t_spa() {
	local c p asset
	c=$(hc "$W/index" GET /console/)
	check spa-index "GET /console/" "$c" 200
	case $(header_get "$W/index.h" Content-Type) in
		text/html*) record PASS spa-index-type "text/html" ;;
		*) record FAIL spa-index-type "GET /console/ is not text/html" ;;
	esac
	p=$(security_header_problems "$W/index.h")
	if [ -z "$p" ]; then record PASS spa-index-headers "CSP, nosniff, X-Frame-Options, Referrer-Policy"; else record FAIL spa-index-headers "$p"; fi
	asset=$(grep -oE '/console/assets/[^"]+\.js' "$W/index" | head -1)
	if [ -z "$asset" ]; then record FAIL spa-asset "no /console/assets/*.js in index.html"; return; fi
	c=$(hc "$W/asset" GET "$asset")
	check spa-asset "GET $asset" "$c" 200
	p=$(security_header_problems "$W/asset.h")
	case $(header_get "$W/asset.h" Cache-Control) in *immutable*) ;; *) p="$p not immutable" ;; esac
	if [ -z "${p# }" ]; then record PASS spa-asset-headers "security headers, immutable cache"; else record FAIL spa-asset-headers "${p# }"; fi
}

t_auth() {
	local pair email role tok c path
	ADMIN_TOKEN=$(login "$ADMIN" "$PASSWORD")
	if [ "$(printf '%s' "$ADMIN_TOKEN" | tr -cd . | wc -c | tr -d ' ')" = 2 ] &&
		[ "$(jwt_payload "$ADMIN_TOKEN" | jq -r .role)" = SUPERADMIN ]; then
		record PASS login-superadmin "POST /api/auth/login returns a SUPERADMIN JWT"
	else
		record FAIL login-superadmin "no SUPERADMIN JWT for $ADMIN"; return 1
	fi
	ADMIN_SUB=$(jwt_payload "$ADMIN_TOKEN" | jq -r .sub)
	TOKENS="admin=$ADMIN_TOKEN"

	c=$(hc "$W/a" GET /api/console/status); check anonymous-401 "GET /api/console/status" "$c" 401
	c=$(hc "$W/a" POST /api/console/stream -H "$(json)" -d '{"subscriptions":[{"id":"s","kind":"status"}]}')
	check anonymous-401-stream "POST /api/console/stream" "$c" 401
	# A token in the URL is never read (only the Authorization header is). A minted token of its
	# own, so the log scan can tell this one apart.
	QUERY_TOKEN=$(jwt_mint "$JWT_SECRET" "$(jq -nc --arg s "$ADMIN_SUB" '{sub:$s,role:"SUPERADMIN",iat:(now|floor),exp:((now|floor)+600)}')")
	TOKENS="$TOKENS query-token=$QUERY_TOKEN"
	c=$(hc "$W/a" GET /api/console/status -H "$(auth "$QUERY_TOKEN")"); check minted-token "a token minted with the dev secret is accepted in the header" "$c" 200
	c=$(hc "$W/a" GET "/api/console/status?token=$QUERY_TOKEN"); check query-token-401 "GET /api/console/status?token=<jwt>" "$c" 401

	for path in /api/console/status /api/console/ws-events/orders /api/api/v1/files; do
		c=$(hc "$W/a" GET "$path" -H "$(auth "$ADMIN_TOKEN")"); check superadmin-200 "GET $path" "$c" 200
	done
	c=$(hc "$W/a" POST /api/console/logs/query -H "$(auth "$ADMIN_TOKEN")" -H "$(json)" -d '{}')
	check superadmin-200 "POST /api/console/logs/query" "$c" 200

	for pair in $OTHER_ROLES; do
		email=${pair%=*} role=${pair#*=}
		tok=$(login "$email" "$PASSWORD")
		if [ -z "$tok" ]; then record FAIL "role-$role" "login failed for $email (is the stack seeded?)"; continue; fi
		TOKENS="$TOKENS $role=$tok"
		[ "$role" = ADMIN_SYSTEM ] && SYSTEM_TOKEN=$tok
		for path in "GET /api/console/status" "GET /api/console/ws-events/orders" "GET /api/api/v1/files"; do
			c=$(hc "$W/a" GET "${path#* }" -H "$(auth "$tok")"); check "role-$role-403" "$path" "$c" 403
		done
		c=$(hc "$W/a" POST /api/console/logs/query -H "$(auth "$tok")" -H "$(json)" -d '{}')
		check "role-$role-403" "POST /api/console/logs/query" "$c" 403
		c=$(hc "$W/a" POST /api/console/stream -H "$(auth "$tok")" -H "$(json)" -d '{"subscriptions":[{"id":"s","kind":"status"}]}')
		check "role-$role-403" "POST /api/console/stream" "$c" 403
	done
}

t_status() {
	local c down
	status_up() {
		c=$(hc "$W/status" GET /api/console/status -H "$(auth "$ADMIN_TOKEN")")
		[ "$c" = 200 ] && [ -z "$(jq -r '[.services[] | select(.state != "UP") | .name] | join(" ")' "$W/status")" ]
	}
	if until_ok 120 status_up; then
		record PASS console-status "every service UP: $(jq -r '[.services[].name] | join(" ")' "$W/status")"
	else
		down=$(jq -r '[.services[]? | select(.state != "UP") | "\(.name)=\(.state)"] | join(" ")' "$W/status" 2>/dev/null)
		record FAIL console-status "HTTP $c, not UP: ${down:-?}"
	fi
}

t_catalog() {
	local c want missing="" m all sys
	c=$(hc "$W/catalog" GET /api/metrics/catalog -H "$(auth "$ADMIN_TOKEN")")
	check metrics-catalog "GET /api/metrics/catalog" "$c" 200
	want="http.server.duration mongo.command.duration kafka.messages ws.sessions ws.messages jvm.heap.used jvm.threads
		jvm.gc.time tomcat.threads tomcat.connections http.server.active mongo.pool.connections mongo.pool.checkout
		bridge.messages bridge.callback.duration bridge.consumer.lag job.run"
	for m in $want; do
		jq -e --arg m "$m" 'any(.metrics[]; .name == $m)' "$W/catalog" >/dev/null || missing="$missing $m"
	done
	all=$(jq '.metrics | length' "$W/catalog" 2>/dev/null)
	if [ -z "$missing" ]; then record PASS metrics-catalog-names "$all metrics, the 17 console metrics listed"
	else record FAIL metrics-catalog-names "missing:$missing"; fi
	if [ -n "${SYSTEM_TOKEN:-}" ]; then
		hc "$W/catalog-sys" GET /api/metrics/catalog -H "$(auth "$SYSTEM_TOKEN")" >/dev/null
		sys=$(jq '.metrics | length' "$W/catalog-sys" 2>/dev/null)
		if [ -n "$sys" ] && [ "$sys" -lt "$all" ]; then record PASS metrics-catalog-hidden "ADMIN_SYSTEM sees $sys of $all"
		else record FAIL metrics-catalog-hidden "ADMIN_SYSTEM sees ${sys:-?} of $all (SUPERADMIN-only metrics not hidden)"; fi
	fi
}

t_traffic() {
	local i c
	RID="smoke$(date +%s)$$"
	for i in 1 2 3 4 5 6 7 8; do
		c=$(hc "$W/p" GET /api/products -H "$(auth "$ADMIN_TOKEN")" -H "X-Request-Id: $RID")
	done
	check traffic "GET /api/products x8 with X-Request-Id" "$c" 200
	check request-id-echo "X-Request-Id echoed through nginx" "$(header_get "$W/p.h" X-Request-Id)" "$RID"
	c=$(hc "$W/prod" POST /api/products -H "$(auth "$ADMIN_TOKEN")" -H "$(json)" -d "$(jq -nc --arg s "SMOKE-$RID" \
		'{sku:$s,name:"Smoke product",category:"HERRAMIENTAS",description:"console smoke",price:{amount_cents:100,currency:"USD",tax_included:true},max_quantity_per_order:5,minimum_stock:1,height:1,width:1,length:1,weight:1}')")
	check traffic-create "POST /api/products" "$c" 201
}

t_upload() {
	local c key
	python3 -c 'import base64,sys; open(sys.argv[1],"wb").write(base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="))' "$W/smoke.png"
	c=$(hc "$W/up" POST /api/api/v1/files/upload -H "$(auth "$ADMIN_TOKEN")" -F "file=@$W/smoke.png;type=image/png" -F path=smoke)
	key=$(jq -r '.key // empty' "$W/up" 2>/dev/null)
	if [ "$c" = 200 ] && [ "${key%%/*}" = smoke ]; then record PASS upload-path "POST /api/api/v1/files/upload with path=smoke -> $key"
	else record FAIL upload-path "HTTP $c, key '${key}'"; return; fi
	c=$(hc "$W/files" GET /api/api/v1/files -H "$(auth "$ADMIN_TOKEN")")
	if [ "$c" = 200 ] && jq -e --arg k "$key" 'any(.files[]; .path + "/" + .filename == $k)' "$W/files" >/dev/null 2>&1; then
		record PASS files-list "the upload is listed"
	else record FAIL files-list "HTTP $c, $key not in GET /api/api/v1/files"; fi
	c=$(hc "$W/img" GET "/api/api/v1/files/$key"); check files-serve "GET /api/api/v1/files/$key (public)" "$c" 200
}

t_metric() {
	local body
	metric_has_products() {
		# Fresh traffic on every try: a rate needs the counter to grow between two exports, so the
		# first burst alone (a new series) never shows up.
		hc "$W/p" GET /api/products -H "$(auth "$ADMIN_TOKEN")" >/dev/null
		body=$(jq -nc '{metric:"http.server.duration",from:(now - 900 | floor | todate),to:(now | floor | todate),
			step:"30s",agg:"p95",group_by:["route"]}')
		[ "$(hc "$W/mq" POST /api/metrics/query -H "$(auth "$ADMIN_TOKEN")" -H "$(json)" -d "$body")" = 200 ] &&
			jq -e 'any(.series[]; .labels.route == "/products" and any(.points[]; .[1] != null))' "$W/mq" >/dev/null
	}
	# The backend exports every 10 s and VictoriaMetrics answers at 10 s resolution.
	if until_ok 120 metric_has_products; then record PASS metric-query "http.server.duration p95 by route has /products"
	else record FAIL metric-query "no /products series within 120 s: $(head -c 200 "$W/mq")"; fi
}

t_logs() {
	logs_have_rid() {
		[ "$(hc "$W/lq" POST /api/console/logs/query -H "$(auth "$ADMIN_TOKEN")" -H "$(json)" \
			-d "$(jq -nc --arg r "$RID" '{service:"backend",request_id:$r}')")" = 200 ] &&
			jq -e --arg r "$RID" 'any(.lines[]; .request_id == $r and (.message | test("^http GET /products 200")))' "$W/lq" >/dev/null
	}
	if until_ok 60 logs_have_rid; then record PASS logs-request-id "access line 'http GET /products 200' with request_id"
	else record FAIL logs-request-id "no access line for request_id $RID within 60 s: $(head -c 200 "$W/lq")"; fi
}

# S1: status + ws-events + metric + an unknown metric, on the real login token.
t_stream_main() {
	local hit t ttfb trigger c
	hit=$(stream_wait s1 snapshot orders 15)
	if [ -z "$hit" ]; then record FAIL stream-snapshot "no snapshot for 'orders' within 15 s (HTTP $(http_status "$W/s1.h"))"; return; fi
	check stream-http "200 text/event-stream" "$(http_status "$W/s1.h") $(header_get "$W/s1.h" Content-Type | cut -d';' -f1)" "200 text/event-stream"
	ttfb=$(head -1 "$W/s1.raw" | cut -f1)
	if lt "$ttfb" 5; then record PASS stream-ttfb "first byte through nginx after ${ttfb}s"
	else record FAIL stream-ttfb "first byte after ${ttfb}s (nginx buffering?)"; fi
	for t in status m; do
		if stream_wait s1 snapshot "$t" 15 >/dev/null; then record PASS "stream-snapshot-$t" "snapshot for '$t'"
		else record FAIL "stream-snapshot-$t" "no snapshot for '$t'"; fi
	done
	hit=$(stream_wait s1 error bad 15)
	if [ -n "$hit" ] && [ "$(printf '%s' "${hit#*	}" | jq -r .code)" = UNKNOWN_METRIC ] &&
		! printf '%s' "${hit#*	}" | grep -q 'smoke\.no\.such'; then
		record PASS stream-error-unknown-metric "error UNKNOWN_METRIC, metric not named"
	else record FAIL stream-error-unknown-metric "got '${hit#*	}'"; fi

	# Traffic outside the stream: a WebSocket handshake without a token is rejected and lands in
	# the orders feed, which the stream must push without any new request from this client.
	trigger=$(stream_elapsed s1)
	c=$(HC_TIMEOUT=5 hc "$W/ws" GET /api/ws/v1/orders -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
		-H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: c21va2Utc21va2Utc21va2U=')
	hit=$(stream_wait s1 update orders 15 "$trigger")
	if [ -n "$hit" ] && printf '%s' "${hit#*	}" | grep -q reject; then
		record PASS stream-update-push "update for 'orders' $(awk -v a="${hit%%	*}" -v b="$trigger" 'BEGIN { printf "%.1f", a - b }')s after the rejected handshake (HTTP $c)"
	else record FAIL stream-update-push "no 'orders' update after the rejected handshake (HTTP $c)"; fi

	hit=$(stream_wait s1 comment "" 25)
	if [ -n "$hit" ] && lt "${hit%%	*}" 20; then record PASS stream-ping "': ${hit#*	}' after ${hit%%	*}s, unbuffered"
	else record FAIL stream-ping "no heartbeat within 20 s"; fi
	stream_stop s1
}

# S2: a minted token that expires 25 s after it opens: the stream must end with token-expired.
t_stream_expiry() {
	local hit
	if ! stream_wait s2 snapshot st 15 >/dev/null; then record FAIL stream-expiry "no snapshot (HTTP $(http_status "$W/s2.h"))"; return; fi
	hit=$(stream_wait s2 end "" 45)
	if [ -n "$hit" ] && [ "$(printf '%s' "${hit#*	}" | jq -r .reason)" = token-expired ] && lt "${hit%%	*}" 35 &&
		stream_wait s2 exit "" 5 >/dev/null; then
		record PASS stream-expiry "end token-expired after ${hit%%	*}s, response closed"
	else record FAIL stream-expiry "got '${hit#*	}' at ${hit%%	*}"; fi
}

# The per-user cap: ten open streams; an eleventh is admitted and the oldest one ends with `limit`.
t_stream_cap() {
	local i hit reason ok=1
	# One at a time, each after its snapshot: the server registers them in this order, so cap1 is the oldest.
	for i in $(seq 1 10); do
		stream_open "cap$i" "$CAP_TOKEN" '{"subscriptions":[{"id":"st","kind":"status"}]}' 60
		stream_wait "cap$i" snapshot st 15 >/dev/null || ok=0
	done
	if [ "$ok" = 0 ]; then record FAIL stream-limit "the first ten streams did not all open"; return; fi
	stream_open cap11 "$CAP_TOKEN" '{"subscriptions":[{"id":"st","kind":"status"}]}' 60
	if stream_wait cap11 snapshot st 15 >/dev/null; then record PASS stream-limit "11th stream of one user is admitted"
	else record FAIL stream-limit "11th stream of one user got no snapshot"; fi
	hit=$(stream_wait cap1 end "" 15)
	reason=$(printf '%s' "${hit#*	}" | jq -r '.reason // empty' 2>/dev/null)
	check stream-evict "oldest stream of one user ends with limit" "$reason" "limit"
	for i in $(seq 1 11); do stream_stop "cap$i"; done
}

t_devtools() {
	local port c running
	devtool() { # PORT EXPECTED-CODE
		c=$(curl -sS -o /dev/null -D "$W/dt$1.h" -w '%{http_code}' --connect-timeout 5 --max-time 15 "http://$DEVTOOLS_HOST:$1/" 2>>"$W/curl.err")
		[ "$c" = "$2" ]
	}
	running=$(dc --profile dev ps --status running --services 2>/dev/null | grep -cE '^(mongo-express|redpanda-console)$')
	if [ "$running" = 0 ]; then
		for port in 8091 8092; do
			devtool $port 502; check "devtools-$port-off" "no dev profile: :$port has no upstream" "$c" 502
		done
		if [ "$UP" = 1 ]; then
			echo "== starting the dev profile tools =="
			dc --profile dev up -d --wait --wait-timeout 180 mongo-express redpanda-console >/dev/null 2>&1 || record FAIL devtools-up "compose up of the dev tools failed"
		else
			record SKIP devtools-on "--no-up: the dev profile tools are not running"; return
		fi
	fi
	for port in 8091 8092; do
		if until_ok 90 devtool $port 200; then
			if [ -z "$(header_get "$W/dt$port.h" X-Frame-Options)" ]; then record PASS "devtools-$port-on" "dev profile: :$port answers 200, frame-safe"
			else record FAIL "devtools-$port-on" ":$port still sends X-Frame-Options"; fi
		else record FAIL "devtools-$port-on" "dev profile: :$port answered $c"; fi
	done
}

t_leaks() {
	local found pair svc detail=""
	dc --profile dev logs --no-color >"$W/compose.log" 2>&1
	# shellcheck disable=SC2086 # TOKENS is a word list of label=value pairs
	found=$(leaks "$W/compose.log" $TOKENS "password=$PASSWORD")
	if [ -z "$found" ]; then record PASS no-secrets-in-logs "$(wc -l <"$W/compose.log" | tr -d ' ') log lines, no JWT or password"; return; fi
	for pair in $TOKENS "password=$PASSWORD"; do
		case " $found " in *" ${pair%%=*} "*) svc=$(leak_services "$W/compose.log" "${pair#*=}"); detail="$detail ${pair%%=*} in [$svc]" ;; esac
	done
	record FAIL no-secrets-in-logs "found:$detail"
}

main() {
	UP=1 KEEP=0
	while [ $# -gt 0 ]; do
		case $1 in
			--no-up) UP=0 ;;
			--keep) KEEP=1 ;;
			-h | --help) sed -n '2,20p' "$0"; exit 0 ;;
			*) echo "unknown option: $1" >&2; exit 2 ;;
		esac
		shift
	done
	for tool in docker curl jq python3; do command -v $tool >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done
	W=${SMOKE_WORKDIR:-$(mktemp -d)}
	mkdir -p "$W"
	trap cleanup EXIT
	trap 'exit 130' INT TERM

	if [ "$UP" = 1 ]; then
		docker info >/dev/null 2>&1 || { echo "Docker daemon is not reachable" >&2; exit 2; }
		echo "== booting the console stack (project $PROJECT) =="
		export ADMIN_EMAIL=$ADMIN ADMIN_PASSWORD=$PASSWORD SEED_DEMO=true INFRA_API_KEY=smoke-api-key \
			INFRA_AMQP_URL=amqp://smoke:smoke@rabbitmq:5672/
		if ! dc up -d --build --wait --wait-timeout "${SMOKE_BOOT_TIMEOUT:-900}" \
			mongodb redpanda minio victoriametrics victorialogs otel-collector backend rabbitmq bridge console restock-cron; then
			record FAIL stack-up "docker compose up --wait failed (see 'docker compose -p $PROJECT ps')"
			summary; exit 1
		fi
	fi
	echo "== waiting for login through $BASE =="
	if ! until_ok 300 login "$ADMIN" "$PASSWORD" >/dev/null; then
		record FAIL login-superadmin "POST $BASE/api/auth/login did not succeed within 300 s"; summary; exit 1
	fi

	echo "== SPA and auth =="
	t_spa
	t_auth || { summary; exit 1; }
	t_status
	t_catalog
	echo "== traffic, upload =="
	t_traffic
	t_upload

	echo "== push stream =="
	local exp
	stream_open s1 "$ADMIN_TOKEN" '{"subscriptions":[{"id":"status","kind":"status"},{"id":"orders","kind":"ws-events","feed":"orders"},{"id":"m","kind":"metric","metric":"http.server.duration","range":"5m","step":"10s","agg":"p95","group_by":["route"]},{"id":"bad","kind":"metric","metric":"smoke.no.such.metric","range":"5m","step":"10s","agg":"avg"}]}' 45
	exp=$(jq -nc --arg s "$ADMIN_SUB" '{sub:$s,role:"SUPERADMIN",iat:(now|floor),exp:((now|floor)+25)}')
	EXP_TOKEN=$(jwt_mint "$JWT_SECRET" "$exp")
	CAP_TOKEN=$(jwt_mint "$JWT_SECRET" "$(jq -nc --arg s "smoke-cap-$$" '{sub:$s,role:"SUPERADMIN",iat:(now|floor),exp:((now|floor)+300)}')")
	TOKENS="$TOKENS expiring=$EXP_TOKEN cap=$CAP_TOKEN"
	stream_open s2 "$EXP_TOKEN" '{"subscriptions":[{"id":"st","kind":"status"}]}' 60
	t_stream_cap
	t_stream_main
	t_stream_expiry

	echo "== metrics and logs pipelines =="
	t_metric
	t_logs

	echo "== dev tools =="
	t_devtools
	echo "== secrets in logs =="
	t_leaks

	summary
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
	main "$@"
fi
