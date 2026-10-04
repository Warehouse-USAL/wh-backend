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
# Env (defaults in docker-compose.yml):
#   RESTOCK_BACKEND_URL  RESTOCK_USER  RESTOCK_PASSWORD
#   RESTOCK_SCHEDULE     cron expression, evaluated in UTC (the image has no tzdata)
#   RESTOCK_ALPHA RESTOCK_RECENT_DAYS RESTOCK_LONG_DAYS RESTOCK_SAFETY_DAYS
#   RESTOCK_LEAD_TIME_DAYS RESTOCK_COVERAGE_DAYS
#   RESTOCK_RUN_ON_START     run once at container start (default true)
#   RESTOCK_STARTUP_RETRIES  attempts for that first run while the backend boots (default 30)
# ---------------------------------------------------------------------------
set -eu

ENV_FILE=/tmp/restock-cron.env

log() { echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) restock-cron: $*"; }

# Escapes a value for embedding inside a JSON string.
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

run_once() {
  [ -f "$ENV_FILE" ] && . "$ENV_FILE"

  login_body=$(printf '{"email":"%s","password":"%s"}' \
    "$(json_escape "$RESTOCK_USER")" "$(json_escape "$RESTOCK_PASSWORD")")
  login=$(curl -sS -m 30 -X POST "$RESTOCK_BACKEND_URL/auth/login" \
    -H 'Content-Type: application/json' -d "$login_body") || { log "login request failed"; return 1; }
  token=$(printf '%s' "$login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
  if [ -z "$token" ]; then
    log "login rejected: $login"
    return 1
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
    return 1
  fi

  products=$(printf '%s' "$payload" | grep -o '"product_id"' | wc -l | tr -d ' ')
  to_restock=$(printf '%s' "$payload" | grep -o '"should_restock":true' | wc -l | tr -d ' ')
  log "applied with params $body — $products products updated, $to_restock to restock"
}

case "${1:-run}" in
  entrypoint)
    # crond starts jobs with an empty environment: persist ours for run_once to source.
    export -p | grep ' RESTOCK_' > "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    if [ "${RESTOCK_RUN_ON_START:-true}" = "true" ]; then
      attempt=1
      retries=${RESTOCK_STARTUP_RETRIES:-30}
      until run_once; do
        if [ "$attempt" -ge "$retries" ]; then
          log "first run failed $attempt times; continuing with the schedule"
          break
        fi
        attempt=$((attempt + 1))
        sleep 10
      done
    fi

    mkdir -p /etc/crontabs
    # Job output to PID 1's stdout, so it shows up in `docker logs`.
    echo "$RESTOCK_SCHEDULE /bin/sh $0 run > /proc/1/fd/1 2>&1" > /etc/crontabs/root
    log "scheduled '$RESTOCK_SCHEDULE' (UTC)"
    exec crond -f -l 8
    ;;
  run)
    run_once
    ;;
  *)
    echo "usage: $0 entrypoint|run" >&2
    exit 2
    ;;
esac
