#!/bin/sh
# Which components a PR touches, for the path-aware jobs in .github/workflows/ci.yml.
# Usage: scripts/ci-changes.sh [base-ref]   (default: origin/$GITHUB_BASE_REF)
# Prints backend=, bridge=, console=, platform= (true|false), also appended to $GITHUB_OUTPUT.
# Diffs from the merge base, so commits that landed on the base meanwhile are not counted.
# --no-renames lists a rename as delete + add, so the old path counts too.
set -eu

base=${1:-origin/${GITHUB_BASE_REF:?pass a base ref or set GITHUB_BASE_REF}}
files=$(git -c core.quotePath=false diff --name-only --no-renames "$base...HEAD")

backend=false bridge=false console=false platform=false
while IFS= read -r f; do
	case $f in
	# Changing CI itself (or the Makefile it mirrors) re-runs everything.
	.github/workflows/ci.yml | scripts/ci-changes.sh | Makefile)
		backend=true bridge=true console=true platform=true ;;
	backend/*) backend=true ;;
	bridge/* | e2e/*) bridge=true ;;
	# The bridge job runs the coverage gate, so it re-runs when the gate changes.
	scripts/go-coverage.sh | scripts/go-coverage.test.sh) bridge=true platform=true ;;
	console/*) console=true ;;
	docker-compose*.yml | .env.example | config/* | .github/workflows/* | scripts/*) platform=true ;;
	esac
done <<EOF
$files
EOF

out="backend=$backend
bridge=$bridge
console=$console
platform=$platform"
echo "$out"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
	echo "$out" >>"$GITHUB_OUTPUT"
fi
