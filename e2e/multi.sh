#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# e2e/multi.sh — the multi-instance group (docs/E2E.md, "Multiple backend instances").
#
# Same stack as e2e/suite.sh plus a second backend on the same MongoDB / Redpanda / MinIO (project
# `whmulti` by default, so it never collides with a running `make e2e-suite`), then runs only the tests
# marked `multi`. Everything is torn down afterwards (KEEP=1 leaves it up).
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.."

export E2E_PROJECT="${E2E_PROJECT:-whmulti}"
export E2E_EXTRA_COMPOSE="e2e/docker-compose.multi.yml"
export E2E_EXTRA_SERVICES="backend2"
export PYTEST_ARGS="${PYTEST_ARGS:--m multi -rP}"
exec e2e/suite.sh "$@"
