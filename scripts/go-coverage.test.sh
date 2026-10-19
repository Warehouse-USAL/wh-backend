#!/bin/sh
# Tests scripts/go-coverage.sh against a throwaway Go module whose coverage is known: internal/a
# is 50% covered, internal/fakeinfra is untested (25% overall if it were counted).
# Usage: sh scripts/go-coverage.test.sh
set -eu

script="$(cd "$(dirname "$0")" && pwd)/go-coverage.sh"
mod=$(mktemp -d)
trap 'rm -rf "$mod"' EXIT
mkdir -p "$mod/internal/a" "$mod/internal/fakeinfra"
printf 'module example.com/m\n\ngo 1.22\n' >"$mod/go.mod"
printf 'package a\n\nfunc One() int { return 1 }\n\nfunc Two() int { return 2 }\n' >"$mod/internal/a/a.go"
printf 'package a\n\nimport "testing"\n\nfunc TestOne(t *testing.T) {\n\tif One() != 1 {\n\t\tt.Fatal()\n\t}\n}\n' >"$mod/internal/a/a_test.go"
printf 'package fakeinfra\n\nfunc X() int { return 1 }\n\nfunc Y() int { return 2 }\n' >"$mod/internal/fakeinfra/f.go"

fails=0
# expect <name> <want-exit: 0|fail> <output-substring> <min>
expect() {
	name=$1 want=$2 needle=$3 min=$4
	if out=$(sh "$script" "$mod" "$min" 2>&1); then rc=0; else rc=fail; fi
	if [ "$rc" = "$want" ] && echo "$out" | grep -qF "$needle"; then
		echo "ok   $name"
	else
		echo "FAIL $name: exit $rc (want $want), output:"
		echo "$out"
		fails=$((fails + 1))
	fi
}

expect above-min 0 "total coverage: 50.0% (minimum 40%)" 40
expect at-min 0 "total coverage: 50.0%" 50
expect just-below fail "FAIL: coverage 50.0% is below 50.1%" 50.1
expect far-below fail "below 99%" 99
if [ ! -f "$mod/coverage.out" ]; then
	echo "FAIL profile-kept"
	fails=$((fails + 1))
elif grep -q fakeinfra "$mod/coverage.out"; then
	echo "FAIL fakeinfra-excluded"
	fails=$((fails + 1))
else
	echo "ok   fakeinfra-excluded"
fi

printf 'package a\n\nimport "testing"\n\nfunc TestTwo(t *testing.T) { t.Fatal("boom") }\n' >"$mod/internal/a/b_test.go"
expect failing-test fail "boom" 0

[ "$fails" -eq 0 ] || { echo "$fails failed"; exit 1; }
echo "all passed"
