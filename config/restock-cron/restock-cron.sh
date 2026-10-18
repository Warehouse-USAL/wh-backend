#!/bin/sh
# ---------------------------------------------------------------------------
# restock-cron — the daily restock run (docs/RFC_Metricas_Calculadas.md).
#
# Logs in with a service account and calls POST /metrics/restock-suggestions/apply
# with the params below, which stores each active product's recommendation on the
# product (product.restock). The params live here, in env vars, not in the backend:
# change one in .env and `docker compose up -d restock-cron` — the backend is untouched.
#
#   restock-cron.sh entrypoint   container entrypoint: optional first run, then crond
#   restock-cron.sh run          one run (what crond executes)
#
# Exit status of a run: 0 ok, 1 transient (backend unreachable / not ready — retried at start),
# 2 rejected request (bad params or credentials — retrying cannot help).
#
# Env (defaults in docker-compose.yml):
#   RESTOCK_BACKEND_URL  RESTOCK_USER  RESTOCK_PASSWORD
#   RESTOCK_SCHEDULE     cron expression, evaluated in UTC (the image has no tzdata)
#   RESTOCK_ALPHA RESTOCK_RECENT_DAYS RESTOCK_LONG_DAYS RESTOCK_SAFETY_DAYS
#   RESTOCK_LEAD_TIME_DAYS RESTOCK_COVERAGE_DAYS
#   RESTOCK_RUN_ON_START     run once at container start (default true)
#   RESTOCK_STARTUP_RETRIES  attempts (10s apart) for the first run on transient errors (default 60)
#   RESTOCK_READY_TIMEOUT    seconds to wait for backend readiness before applying (default 900)
#   RESTOCK_OTLP_URL         OTLP/HTTP metrics URL for the job.run gauge; blank = no push
#
# After each run (the startup run counts once, however many attempts it took) two job.run gauge
# points go to the collector: field=duration_ms and field=suggestions (rows in the apply response,
# 0 on failure), both tagged job=restock and result=ok|error. A failed push never fails the run.
# ---------------------------------------------------------------------------
set -eu

ENV_FILE=/tmp/restock-cron.env

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) restock-cron: $*"; }

# Escapes a value for embedding inside a JSON string: backslash, quote, tab, CR and newline.
json_escape() {
  printf '%s' "$1" \
    | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' -e "s/$(printf '\t')/\\\\t/g" -e "s/$(printf '\r')/\\\\r/g" \
    | awk '{ printf "%s%s", (NR > 1 ? "\\n" : ""), $0 }'
}

# Waits until the backend reports itself ready. Its HTTP port opens before startup runners
# finish — the admin bootstrap and the demo seed — so logging in or applying earlier could be
# refused, or compute on half-inserted data. Returns 1 (transient) on timeout.
wait_ready() {
  waited=0
  while :; do
    ready=$(curl -s -m 10 -o /dev/null -w '%{http_code}' \
      "$RESTOCK_BACKEND_URL/actuator/health/readiness") || ready=000
    [ "$ready" = "200" ] && break
    if [ "$waited" -ge "${RESTOCK_READY_TIMEOUT:-900}" ]; then
      log "backend not ready after ${waited}s (readiness HTTP $ready)"
      return 1
    fi
    sleep 5
    waited=$((waited + 5))
  done
  if [ "$waited" -gt 0 ]; then
    log "backend ready after ${waited}s"
  fi
}

# Milliseconds from a monotonic clock (busybox date has no %N).
now_ms() {
  awk '{ printf "%d", $1 * 1000 }' /proc/uptime 2>/dev/null || echo $(($(date +%s) * 1000))
}

# POSTs one job.run gauge point. No service.name resource attribute on purpose: the remote-write
# exporter would turn it into the job label and overwrite job=restock. Best effort, a few seconds
# at most, never an error.
push_point() {
  [ -n "${RESTOCK_OTLP_URL:-}" ] || return 0
  point=$(printf '{"resourceMetrics":[{"scopeMetrics":[{"scope":{"name":"restock-cron"},"metrics":[{"name":"job.run","gauge":{"dataPoints":[{"timeUnixNano":"%s000000000","asInt":"%s","attributes":[{"key":"job","value":{"stringValue":"restock"}},{"key":"result","value":{"stringValue":"%s"}},{"key":"field","value":{"stringValue":"%s"}}]}]}}]}]}]}' \
    "$(date +%s)" "$3" "$1" "$2")
  curl -s -o /dev/null --connect-timeout 2 -m 5 -X POST "$RESTOCK_OTLP_URL" \
    -H 'Content-Type: application/json' -d "$point" || log "job.run push failed (ignored)"
}

# Reports a finished run: $1 = run_once status, $2 = its start (now_ms).
report_run() {
  result=ok
  [ "$1" -eq 0 ] || { result=error; suggestions=0; }
  push_point "$result" duration_ms "$(($(now_ms) - $2))" || true
  push_point "$result" suggestions "${suggestions:-0}" || true
}

run_once() {
  suggestions=0
  [ -f "$ENV_FILE" ] && . "$ENV_FILE"

  wait_ready || return 1

  login_body=$(printf '{"email":"%s","password":"%s"}' \
    "$(json_escape "$RESTOCK_USER")" "$(json_escape "$RESTOCK_PASSWORD")")
  login=$(curl -sS -m 30 -w '\n%{http_code}' -X POST "$RESTOCK_BACKEND_URL/auth/login" \
    -H 'Content-Type: application/json' -d "$login_body") || { log "login request failed"; return 1; }
  login_status=$(printf '%s' "$login" | tail -n 1)
  token=$(printf '%s' "$login" | sed '$d' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -z "$token" ]; then
    log "login failed with HTTP $login_status: $(printf '%s' "$login" | sed '$d')"
    # The backend is ready, so a 4xx is a real rejection (wrong credentials); anything else is not.
    case "$login_status" in 4*) return 2 ;; *) return 1 ;; esac
  fi

  body=$(printf '{"params":{"alpha":%s,"recent_days":%s,"long_days":%s,"safety_days":%s,"lead_time_days":%s,"coverage_days":%s}}' \
    "$RESTOCK_ALPHA" "$RESTOCK_RECENT_DAYS" "$RESTOCK_LONG_DAYS" \
    "$RESTOCK_SAFETY_DAYS" "$RESTOCK_LEAD_TIME_DAYS" "$RESTOCK_COVERAGE_DAYS")
  response=$(curl -sS -m 120 -w '\n%{http_code}' -X POST \
    "$RESTOCK_BACKEND_URL/metrics/restock-suggestions/apply" \
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "$body") \
    || { log "apply request failed"; return 1; }
  status=$(printf '%s' "$response" | tail -n 1)
  payload=$(printf '%s' "$response" | sed '$d')
  if [ "$status" != "200" ]; then
    log "apply failed with HTTP $status: $payload"
    case "$status" in 4*) return 2 ;; *) return 1 ;; esac
  fi

  products=$(printf '%s' "$payload" | grep -o '"product_id"' | wc -l | tr -d ' ')
  to_restock=$(printf '%s' "$payload" | grep -o '"should_restock":true' | wc -l | tr -d ' ')
  suggestions=$products
  log "applied with params $body — $products products updated, $to_restock to restock"
}

case "${1:-run}" in
  entrypoint)
    # crond starts jobs with an empty environment: persist ours for run_once to source.
    export -p | grep ' RESTOCK_' > "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    if [ "${RESTOCK_RUN_ON_START:-true}" = "true" ]; then
      attempt=1
      retries=${RESTOCK_STARTUP_RETRIES:-60}
      while :; do
        rc=0
        started=$(now_ms)
        run_once || rc=$?
        [ "$rc" -eq 0 ] && break
        if [ "$rc" -eq 2 ]; then
          log "request rejected; fix the configuration — not retrying until the next scheduled run"
          break
        fi
        if [ "$attempt" -ge "$retries" ]; then
          log "first run failed $attempt times; continuing with the schedule"
          break
        fi
        attempt=$((attempt + 1))
        sleep 10
      done
      report_run "$rc" "$started"
    fi

    mkdir -p /etc/crontabs
    # Job output to PID 1's stdout, so it shows up in `docker logs`.
    echo "$RESTOCK_SCHEDULE /bin/sh $0 run > /proc/1/fd/1 2>&1" > /etc/crontabs/root
    log "scheduled '$RESTOCK_SCHEDULE' (UTC)"
    exec crond -f -l 8
    ;;
  run)
    rc=0
    started=$(now_ms)
    run_once || rc=$?
    report_run "$rc" "$started"
    exit "$rc"
    ;;
  *)
    echo "usage: $0 entrypoint|run" >&2
    exit 2
    ;;
esac
