#!/bin/sh
# Tests scripts/ci-changes.sh against a throwaway git repo. Usage: sh scripts/ci-changes.test.sh
set -eu

script="$(cd "$(dirname "$0")" && pwd)/ci-changes.sh"
repo=$(mktemp -d)
trap 'rm -rf "$repo"' EXIT
cd "$repo"
git init -q -b main
git config user.email ci@example.com
git config user.name ci
git config commit.gpgsign false
mkdir -p backend bridge e2e docs .github/workflows scripts
echo a >backend/A.java
echo b >bridge/b.go
echo e >e2e/run.sh
echo d >docs/x.md
echo w >.github/workflows/ci.yml
echo s >scripts/ci-changes.sh
git add -A
git commit -qm base
git update-ref refs/remotes/origin/main main

fails=0
# check <name> <expected "b br c p" as t/f> -- <shell commands run on a fresh branch from main>
check() {
	name=$1 want=$2
	shift 3
	git checkout -q -B "t-$name" main
	sh -c "$*"
	git add -A
	git commit -qm "$name" --allow-empty
	got=$(sh "$script" main | sed 's/.*=true/t/; s/.*=false/f/' | tr '\n' ' ' | sed 's/ $//')
	if [ "$got" = "$want" ]; then
		echo "ok   $name"
	else
		echo "FAIL $name: want '$want', got '$got'"
		fails=$((fails + 1))
	fi
}

check docs-only "f f f f" -- 'echo x >>docs/x.md; echo r >README.md'
check no-changes "f f f f" -- ':'
check backend-only "t f f f" -- 'echo x >>backend/A.java'
check bridge-only "f t f f" -- 'echo x >>bridge/b.go'
check e2e-only "f t f f" -- 'echo x >>e2e/run.sh'
check console-only "f f t f" -- 'mkdir -p console/src; echo x >console/src/a.ts'
check compose-file "f f f t" -- 'echo x >docker-compose.prod.yml'
check config-dir "f f f t" -- 'mkdir -p config/otel; echo x >config/otel/c.yml'
check other-workflow "f f f t" -- 'echo x >.github/workflows/deploy.yml'
check other-script "f f f t" -- 'echo x >scripts/smoke.sh'
check go-coverage-script "f t f t" -- 'echo x >scripts/go-coverage.sh'
check go-coverage-test "f t f t" -- 'echo x >scripts/go-coverage.test.sh'
check env-example "f f f t" -- 'echo x >.env.example'
check non-ascii-name "t f f f" -- 'echo x >backend/Café.java'
check ci-workflow "t t t t" -- 'echo x >.github/workflows/ci.yml'
check ci-changes-script "t t t t" -- 'echo x >scripts/ci-changes.sh'
check makefile "t t t t" -- 'echo x >Makefile'
check multiple "t f t f" -- 'echo x >>backend/A.java; mkdir -p console; echo x >console/p.json'
check rename-out "t f f f" -- 'git mv backend/A.java docs/A.java'
check delete "f t f f" -- 'git rm -q bridge/b.go'
check nested-name "f f f f" -- 'mkdir -p docs/backend; echo x >docs/backend/n.md'

# Commits that landed on the base after the branch point are not the PR's changes.
git checkout -q -B t-diverged main
mkdir -p console
echo x >console/a.ts
git add -A
git commit -qm console
git checkout -q main
echo y >>backend/A.java
git commit -qam "backend on main"
git checkout -q t-diverged
got=$(sh "$script" main | tr '\n' ' ')
if [ "$got" = "backend=false bridge=false console=true platform=false " ]; then
	echo "ok   merge-base"
else
	echo "FAIL merge-base: got '$got'"
	fails=$((fails + 1))
fi

# Default base is origin/$GITHUB_BASE_REF, and the lines are appended to $GITHUB_OUTPUT.
out="$repo/.gh-output"
echo "pre=1" >"$out"
GITHUB_BASE_REF=main GITHUB_OUTPUT="$out" sh "$script" >/dev/null
if [ "$(cat "$out")" = "pre=1
backend=false
bridge=false
console=true
platform=false" ]; then
	echo "ok   github-output"
else
	echo "FAIL github-output: $(cat "$out")"
	fails=$((fails + 1))
fi

# A missing base ref is an error, never "nothing changed".
if sh "$script" origin/nope >/dev/null 2>&1; then
	echo "FAIL bad-base: exited 0"
	fails=$((fails + 1))
else
	echo "ok   bad-base"
fi

[ "$fails" -eq 0 ] || { echo "$fails failed"; exit 1; }
echo "all passed"
