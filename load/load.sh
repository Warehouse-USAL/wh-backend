#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# load.sh — load-test harness (docs/LOAD_TESTING.md). NOT part of the PR checks.
#
# Starts the stack (SEED_DEMO=true: ~25k orders over 730 days) in its own compose project (default `whload`, no
# host ports, so it can run next to `make e2e-suite`), runs the k6 scenarios in load/k6 and the asyncio ones in
# load/py one at a time while sampling `docker stats`, prints the MongoDB query plans of the hot reads, writes
# load/results/summary.md and tears everything down.
#
# Usage:  load/load.sh                      # everything
#         LOAD_SCENARIOS="02_listing 03_orders" load/load.sh
#         KEEP=1 load/load.sh               # leave the stack up (reuse with LOAD_REUSE=1)
# Env:    LOAD_PROJECT (whload), LOAD_SCENARIOS, SSE_CLIENTS (60), WS_CLIENTS (200), MIN_FREE_GB (10)
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.." || exit 2

export COMPOSE_PROJECT_NAME="${LOAD_PROJECT:-whload}"
RES=load/results
mkdir -p "$RES"
STATE="$RES/.env"

free_gb=$(df -k . | awk 'NR==2 { printf "%d", $4 / 1048576 }')
if [ "$free_gb" -lt "${MIN_FREE_GB:-10}" ]; then echo "only ${free_gb} GB free: refusing to start"; exit 2; fi
docker info >/dev/null 2>&1 || { echo "Docker daemon is not reachable."; exit 2; }

if [ "${LOAD_REUSE:-0}" != 1 ] || [ ! -f "$STATE" ]; then
  rand() { LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c "$1"; }
  { echo "JWT_SECRET=$(rand 64)"; echo "INFRA_API_KEY=$(rand 32)"
    echo "ADMIN_EMAIL=admin@smartwarehouse.local"; echo "ADMIN_PASSWORD=Adm1n-$(rand 16)"; } >"$STATE"
fi
# shellcheck disable=SC1090
set -a; . "$STATE"; set +a

DC=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f e2e/docker-compose.suite.yml -f load/docker-compose.load.yml
    --profile suggestions --profile load)
STACK=(backend mongodb redpanda minio suggestions suggestions-redis otel-collector victoriametrics victorialogs)
SCENARIOS=${LOAD_SCENARIOS:-"01_login 02_listing 03_orders 04_metrics 05_suggestions sse ws"}

cleanup() {
  [ -n "${SAMPLER:-}" ] && kill "$SAMPLER" 2>/dev/null
  if [ "${KEEP:-0}" = 1 ]; then echo "KEEP=1: stack left up (project $COMPOSE_PROJECT_NAME)"; return; fi
  "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1
}
trap cleanup EXIT

if [ "${LOAD_REUSE:-0}" != 1 ]; then
  echo "== building + starting the stack (project $COMPOSE_PROJECT_NAME) =="
  "${DC[@]}" build backend suggestions loadpy || exit 1
  "${DC[@]}" up -d "${STACK[@]}" || exit 1
fi
echo "== waiting for the backend =="
"${DC[@]}" run --rm -T loadpy -c "
import httpx, os, sys, time
end = time.time() + 400
while time.time() < end:
    try:
        if httpx.get(os.environ['BACKEND_URL'] + '/actuator/health/readiness', timeout=5).status_code == 200:
            sys.exit(0)
    except httpx.HTTPError:
        pass
    time.sleep(3)
sys.exit('backend not ready')
" || exit 1

sample() {  # sample <name>: docker stats of this project's containers every 2 s until killed
  while :; do
    docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' \
      $(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT_NAME" -q) >>"$RES/stats-$1.csv" 2>/dev/null
    sleep 2
  done
}

rm -f "$RES"/k6-*.json "$RES"/stats-*.csv "$RES"/streams-*.jsonl "$RES"/explain-*.txt
RC=0
for sc in $SCENARIOS; do
  echo "== scenario $sc =="
  sample "$sc" & SAMPLER=$!
  case "$sc" in
    sse) "${DC[@]}" run --rm -T loadpy /load/streams.py sse "${SSE_CLIENTS:-60}" | tee "$RES/streams-sse.jsonl" ;;
    ws) "${DC[@]}" run --rm -T loadpy /load/streams.py ws "${WS_CLIENTS:-200}" | tee "$RES/streams-ws.jsonl" ;;
    *) "${DC[@]}" run --rm -T k6 run --summary-trend-stats "avg,med,p(90),p(95),p(99),max" \
         --summary-export "/results/k6-$sc.json" "/scripts/$sc.js" || RC=1 ;;
  esac
  kill "$SAMPLER" 2>/dev/null; wait "$SAMPLER" 2>/dev/null; SAMPLER=
done

echo "== MongoDB query plans =="
"${DC[@]}" exec -T mongodb mongo --quiet smartwarehouse /load/explain.js | tee "$RES/explain-plans.txt"

"${DC[@]}" run --rm -T -v "$PWD/load:/loadall" loadpy /loadall/summarize.py /loadall/results >/dev/null
echo "== summary: load/results/summary.md (exit $RC; thresholds failing is information, not a CI gate) =="
exit $RC
