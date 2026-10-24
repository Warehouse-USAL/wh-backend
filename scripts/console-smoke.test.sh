#!/usr/bin/env bash
# Tests the helpers of scripts/console-smoke.sh on canned inputs, without Docker or a network.
# Usage: bash scripts/console-smoke.test.sh
set -uo pipefail

# shellcheck source=scripts/console-smoke.sh
. "$(cd "$(dirname "$0")" && pwd)/console-smoke.sh"

T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
fails=0
eq() { # NAME GOT WANT
	if [ "$2" = "$3" ]; then echo "ok   $1"; else
		printf 'FAIL %s\n  want: %s\n  got:  %s\n' "$1" "$3" "$2"; fails=$((fails + 1)); fi
}
tab=$(printf '\t')

# ── SSE parsing over a timestamped capture ──
cat >"$T/raw" <<EOF
0.120${tab}event: snapshot
0.121${tab}data: {"id":"orders","mode":"append","data":{"events":[]}}
0.121${tab}
0.500${tab}data: {"id":"x"}
0.501${tab}
15.002${tab}: ping
16.000${tab}event: update
16.000${tab}data: {"id":"orders","mode":"append","data":{"events":[{"type":"reject"}]}}
16.001${tab}
17.000${tab}event: error
17.000${tab}data: line one
17.000${tab}data: line two
17.000${tab}
25.000${tab}event: end
25.000${tab}data: {"reason":"token-expired"}
25.001${tab}
25.002${tab}event: update
25.002${tab}data: {"id":"cut"}
25.010${tab}#exit 0
EOF
sse_parse "$T/raw" >"$T/sse"
eq "sse: one row per frame and comment, cut frame dropped" "$(cut -f2 "$T/sse" | tr '\n' ' ')" "snapshot message comment update error end exit "
eq "sse: frame time is its first line" "$(sed -n 1p "$T/sse" | cut -f1)" "0.120"
eq "sse: default event name is message" "$(sed -n 2p "$T/sse" | cut -f3)" '{"id":"x"}'
eq "sse: comment text" "$(sed -n 3p "$T/sse" | cut -f1,3)" "15.002${tab}ping"
eq "sse: multi-line data joined" "$(sed -n 5p "$T/sse" | cut -f3)" 'line one\nline two'
eq "sse: exit status" "$(sed -n 7p "$T/sse" | cut -f3)" "0"

eq "find: first snapshot by id" "$(sse_find "$T/sse" snapshot orders | cut -f1)" "0.120"
eq "find: no frame of another id" "$(sse_find "$T/sse" snapshot status)" ""
eq "find: update after a time" "$(sse_find "$T/sse" update orders 10 | cut -f1)" "16.000"
eq "find: nothing after the last update" "$(sse_find "$T/sse" update orders 16)" ""
eq "find: any id (end)" "$(sse_find "$T/sse" end | cut -f2 | jq -r .reason)" "token-expired"
eq "find: non-JSON data never matches an id" "$(sse_find "$T/sse" error orders)" ""
eq "find: comment" "$(sse_find "$T/sse" comment | cut -f2)" "ping"

printf '0.1\tdata: {"id":"a"}\n' >"$T/raw2"
eq "sse: frame without blank line is not dispatched" "$(sse_parse "$T/raw2")" ""

# ── headers ──
printf 'HTTP/1.1 301 Moved\r\nLocation: /console/\r\nX-Frame-Options: SAMEORIGIN\r\n\r\nHTTP/1.1 200 OK\r\ncontent-type: text/html\r\nX-Frame-Options: DENY\r\nSet-Cookie: a=1\r\nSet-Cookie: b=2\r\n\r\n' >"$T/h"
eq "header: last response only, CR stripped" "$(header_get "$T/h" X-Frame-Options)" "DENY"
eq "header: case-insensitive" "$(header_get "$T/h" Content-Type)" "text/html"
eq "header: repeated values one per line" "$(header_get "$T/h" set-cookie | tr '\n' ,)" "a=1,b=2,"
eq "header: absent" "$(header_get "$T/h" Content-Security-Policy)" ""
eq "status: last response" "$(http_status "$T/h")" "200"

good="default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-src 'self' http://*:8091 http://*:8092; base-uri 'self'; object-src 'none'; frame-ancestors 'none'"
eq "csp: the console's policy passes" "$(csp_problems "$good")" ""
eq "csp: missing directives listed" "$(csp_problems "default-src *; object-src 'none'")" "missing(default-src 'self') missing(base-uri 'self') missing(frame-ancestors 'none')"
eq "csp: unsafe script sources flagged" "$(csp_problems "$good; script-src 'self' 'unsafe-inline' 'unsafe-eval'")" "unsafe-eval script-unsafe-inline"

printf 'HTTP/1.1 200 OK\r\nContent-Security-Policy: %s\r\nX-Content-Type-Options: nosniff\r\nX-Frame-Options: DENY\r\nReferrer-Policy: strict-origin-when-cross-origin\r\n\r\n' "$good" >"$T/ok.h"
eq "security headers: complete set" "$(security_header_problems "$T/ok.h")" ""
printf 'HTTP/1.1 200 OK\r\nContent-Security-Policy: %s\r\nContent-Security-Policy: %s\r\n\r\n' "$good" "$good" >"$T/bad.h"
eq "security headers: duplicate CSP and missing ones" "$(security_header_problems "$T/bad.h")" "several CSP headers nosniff X-Frame-Options Referrer-Policy"
printf 'HTTP/1.1 200 OK\r\n\r\n' >"$T/none.h"
eq "security headers: none" "$(security_header_problems "$T/none.h")" "no CSP nosniff X-Frame-Options Referrer-Policy"

# ── JWT ── (RFC 7519 style vector, the widely published HS256 example)
eq "jwt: HS256 known vector" "$(jwt_mint your-256-bit-secret '{"sub":"1234567890","name":"John Doe","iat":1516239022}')" \
	"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
eq "jwt: payload round trip (padding)" "$(jwt_payload "$(jwt_mint s '{"sub":"ab","exp":1}')" | jq -c .)" '{"sub":"ab","exp":1}'

# ── stamp: timestamps each line and records the exit status ──
py stamp "$T/st" printf 'a\nb\n'
eq "stamp: lines then exit" "$(cut -f2 "$T/st" | tr '\n' ' ')" "a b #exit 0 "
eq "stamp: start time written" "$(grep -cE '^[0-9]+\.[0-9]{3}$' "$T/st.t0")" "1"

# ── leak scan: names only, values never printed ──
printf 'backend-1  | login ok\nconsole-1  | "GET /api/x?token=SECRETJWT HTTP/1.1" 401\nbridge-1  | SECRETJWT again\n' >"$T/log"
eq "leaks: labels of the needles found" "$(leaks "$T/log" admin=SECRETJWT other=NOPE password=Demo1234! empty=)" "admin"
eq "leaks: services that logged it" "$(leak_services "$T/log" SECRETJWT)" "bridge console"

# ── results table and exit status ──
RESULTS=""
record PASS a "fine" >/dev/null
check b "compare" 1 1 >/dev/null
out=$(summary); rc=$?
eq "summary: all pass exits 0" "$rc" "0"
eq "summary: table rows" "$(printf '%s\n' "$out" | grep -c '^| PASS')" "2"
check c "compare" 1 2 >/dev/null
record SKIP d "skipped" >/dev/null
out=$(summary); rc=$?
eq "summary: a failure exits 1" "$rc" "1"
eq "summary: counts" "$(printf '%s\n' "$out" | tail -1)" "2 passed, 1 failed, 1 skipped"
eq "summary: failure detail" "$(printf '%s\n' "$out" | grep '^| FAIL')" "| FAIL | c | compare: expected '2', got '1' |"

eq "lt: numeric compare" "$(lt 4.9 5 && echo y; lt 5 5 || echo n)" "y
n"

if [ "$fails" -gt 0 ]; then echo "$fails failed"; exit 1; fi
echo "all console-smoke helper tests passed"
