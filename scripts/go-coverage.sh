#!/bin/sh
# Statement coverage gate for a Go module's ./internal/... (internal/fakeinfra, a test double,
# excluded). Usage: scripts/go-coverage.sh <module-dir> <min-percent>
# Leaves the profile at <module-dir>/coverage.out; exits non-zero when the total is below <min>.
set -eu

dir=${1:?usage: go-coverage.sh <module-dir> <min-percent>}
min=${2:?usage: go-coverage.sh <module-dir> <min-percent>}
cd "$dir"

pkgs=$(go list ./internal/... | grep -v '/internal/fakeinfra$')
# shellcheck disable=SC2086 # one package per word
go test -race -coverprofile=coverage.out $pkgs
total=$(go tool cover -func=coverage.out | awk '$1 == "total:" { sub(/%/, "", $NF); print $NF }')

echo "total coverage: ${total}% (minimum ${min}%)"
if ! awk -v t="$total" -v m="$min" 'BEGIN { exit !(t + 0 >= m + 0) }'; then
	echo "FAIL: coverage ${total}% is below ${min}%" >&2
	exit 1
fi
