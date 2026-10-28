#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# e2e/suite.sh — full-system end-to-end suite (docs/E2E.md).
#
# Builds every image from source, starts the whole stack in an isolated compose project with
# SEED_DEMO=true and throwaway secrets, runs e2e/suite (pytest, in a runner container joined to the
# compose network), writes e2e/results/{junit.xml,matrix.md} and tears everything down.
#
# Usage:  e2e/suite.sh                    # whole suite (incl. slow resilience group)
#         PYTEST_ARGS="-m 'not resilience'" e2e/suite.sh
#         PYTEST_ARGS="suite/test_auth.py" e2e/suite.sh
#         KEEP=1 e2e/suite.sh             # leave the stack up (re-run the runner with e2e/suite.sh rerun)
# Env:    E2E_PROJECT (default whsuite). Needs: docker with compose v2. Never touches Infra's broker.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME="${E2E_PROJECT:-whsuite}"
STATE=e2e/results/.env
mkdir -p e2e/results

if [ "${1:-}" != rerun ] || [ ! -f "$STATE" ]; then
  # Throwaway secrets, generated per run. Never read from (or written to) a real .env.
  rand() { LC_ALL=C tr -dc 'a-f0-9' </dev/urandom | head -c "$1"; }
  { echo "JWT_SECRET=$(rand 64)"; echo "INFRA_API_KEY=$(rand 32)"
    echo "ADMIN_EMAIL=admin@smartwarehouse.local"; echo "ADMIN_PASSWORD=Adm1n-$(rand 16)"; } >"$STATE"
fi
set -a; . "$STATE"; set +a
export PYTEST_ARGS="${PYTEST_ARGS:-}"

DC=(docker compose -p "$COMPOSE_PROJECT_NAME" -f docker-compose.yml -f e2e/docker-compose.suite.yml
    --profile infra --profile suggestions --profile runner)
# fakeinfra is created but not started: the suite starts it for the `flow` group only (see conftest.py).
STACK=(backend bridge rabbitmq mongodb redpanda minio console suggestions suggestions-redis
       otel-collector victoriametrics victorialogs restock-cron)

cleanup() {
  if [ "${KEEP:-0}" = 1 ]; then echo "KEEP=1: stack left up (project $COMPOSE_PROJECT_NAME)"; return; fi
  "${DC[@]}" down -v --remove-orphans >/dev/null 2>&1
}
trap cleanup EXIT

docker info >/dev/null 2>&1 || { echo "Docker daemon is not reachable."; exit 2; }

if [ "${1:-}" != rerun ]; then
  echo "== building + starting the stack (project $COMPOSE_PROJECT_NAME) =="
  "${DC[@]}" build backend bridge fakeinfra console suggestions runner || exit 1
  "${DC[@]}" up -d "${STACK[@]}" || exit 1
  "${DC[@]}" create fakeinfra || exit 1
fi

echo "== running the suite =="
"${DC[@]}" run --rm -T runner
RC=$?
echo "== results: e2e/results/matrix.md, e2e/results/junit.xml (exit $RC) =="
exit $RC
