#!/usr/bin/env bash
# Tests scripts/push-image.sh with a fake `docker` on PATH (no registry involved).
# Usage: bash scripts/push-image.test.sh
set -u

src="$(cd "$(dirname "$0")" && pwd)/push-image.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin"
cat > "$work/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
# Fails the first $FAKE_FAILS calls, then succeeds; counts calls in $FAKE_COUNT_FILE.
n=$(cat "$FAKE_COUNT_FILE" 2>/dev/null || echo 0)
n=$((n + 1))
echo "$n" > "$FAKE_COUNT_FILE"
[ "$n" -le "$FAKE_FAILS" ] && { echo "unknown blob" >&2; exit 1; }
exit 0
DOCKER
chmod +x "$work/bin/docker"

fail=0
check() { # name expected-exit expected-calls fails attempts
  local name=$1 want_rc=$2 want_calls=$3 fails=$4 attempts=$5
  rm -f "$work/count"
  PATH="$work/bin:$PATH" FAKE_COUNT_FILE="$work/count" FAKE_FAILS="$fails" \
    bash "$src" ghcr.io/x/y:t "$attempts" 0 >/dev/null 2>&1
  local rc=$? calls
  calls=$(cat "$work/count" 2>/dev/null || echo 0)
  if [ "$rc" -eq "$want_rc" ] && [ "$calls" -eq "$want_calls" ]; then
    echo "ok   $name"
  else
    echo "FAIL $name (exit $rc want $want_rc, calls $calls want $want_calls)"
    fail=1
  fi
}

check "succeeds first time without retrying" 0 1 0 4
check "retries a transient failure and succeeds" 0 3 2 4
check "gives up after the attempt limit" 1 3 99 3
check "a single attempt does not retry" 1 1 99 1

if PATH="$work/bin:$PATH" bash "$src" >/dev/null 2>&1; then
  echo "FAIL missing image argument must fail"; fail=1
else
  echo "ok   missing image argument fails"
fi

[ "$fail" -eq 0 ] && echo "all passed"
exit "$fail"
