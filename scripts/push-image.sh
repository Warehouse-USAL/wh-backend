#!/usr/bin/env bash
# Pushes a container image to the registry, retrying transient failures.
# Usage: scripts/push-image.sh <image:tag> [attempts] [first-delay-seconds]
#
# GHCR sometimes answers `unknown blob` or a 5xx right after the layers were uploaded; a plain
# `docker push` then fails the whole release job (and with it the git tag, the GitHub release and the
# backport to develop). The push is idempotent, so retrying it is safe.
set -u

image="${1:?usage: push-image.sh <image:tag> [attempts] [first-delay-seconds]}"
attempts="${2:-4}"
delay="${3:-10}"

n=1
while true; do
  if docker push "$image"; then
    exit 0
  fi
  if [ "$n" -ge "$attempts" ]; then
    echo "push-image: giving up on $image after $n attempts" >&2
    exit 1
  fi
  echo "push-image: attempt $n of $attempts failed for $image; retrying in ${delay}s" >&2
  sleep "$delay"
  n=$((n + 1))
  delay=$((delay * 2))
done
