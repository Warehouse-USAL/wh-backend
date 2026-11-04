#!/usr/bin/env bash
# Pre-flight for `make deploy` (docs/OPERATIONS.md, "First deploy checklist").
#
#   scripts/prod-env.sh check    validate .env for the prod overlay; exit 1 with a fix-it message
#   scripts/prod-env.sh bridge   print "yes" when the Infra bridge is configured, "no" otherwise
#
# A value is read from the environment first, then from .env (last assignment wins), which is what
# `docker compose` does too. Nothing is ever printed from a secret.
set -euo pipefail

cd "$(dirname "$0")/.."

# Same reading rules as `docker compose`: CRLF tolerated, an optional `export `, a quoted value
# taken verbatim, an unquoted one cut at ` #` (inline comment) and trimmed.
get() {
  local v="${!1:-}" raw
  if [ -z "$v" ] && [ -f .env ]; then
    raw=$(tr -d '\r' <.env | grep -E "^[[:space:]]*(export[[:space:]]+)?$1[[:space:]]*=" | tail -n1 | sed -E 's/^[^=]*=[[:space:]]*//' || true)
    case $raw in
      \"*) v=${raw#\"}; v=${v%%\"*} ;;
      \'*) v=${raw#\'}; v=${v%%\'*} ;;
      *) v=$(printf '%s' "$raw" | sed -E 's/[[:space:]]+#.*$//; s/[[:space:]]+$//') ;;
    esac
  fi
  printf '%s' "$v"
}

bridge_configured() {
  [ -n "$(get INFRA_AMQP_URL)" ] && [ -n "$(get INFRA_API_KEY)" ]
}

check() {
  local errors=() var
  if [ ! -f .env ]; then
    errors+=("no .env file in $(pwd): copy .env.example to .env and fill it in")
  fi
  for var in SPRING_DATA_MONGODB_URI JWT_SECRET MONGO_ROOT_USER MONGO_ROOT_PASSWORD; do
    if [ -z "$(get "$var")" ]; then
      errors+=("$var is not set (the prod overlay refuses to start without it)")
    fi
  done
  if [ -n "$(get JWT_SECRET)" ] && [ "$(get JWT_SECRET | wc -c)" -lt 32 ]; then
    errors+=("JWT_SECRET must be at least 32 characters")
  fi

  local amqp key
  amqp=$(get INFRA_AMQP_URL)
  key=$(get INFRA_API_KEY)
  if [ -n "$amqp" ] && [ -z "$key" ]; then
    errors+=("INFRA_AMQP_URL is set but INFRA_API_KEY is not: the bridge and the backend share that key (generate one with 'make infra-key')")
  fi
  if [ -z "$amqp" ] && [ -n "$key" ]; then
    errors+=("INFRA_API_KEY is set but INFRA_AMQP_URL is not: set the RabbitMQ AMQP URL (amqp://user:pass@host:5672/) or unset the key to run without the bridge")
  fi
  if [[ $amqp =~ :1883([/?]|$) ]]; then
    errors+=("INFRA_AMQP_URL points at port 1883, RabbitMQ's MQTT plugin; the bridge speaks AMQP, port 5672 (symptom: 'dial rabbitmq: Exception (501) Reason: \"EOF\"')")
  fi

  if [ "${#errors[@]}" -gt 0 ]; then
    echo "deploy aborted: .env is not ready for production:" >&2
    for e in "${errors[@]}"; do
      echo "  - $e" >&2
    done
    echo "See docs/OPERATIONS.md (First deploy checklist)." >&2
    exit 1
  fi

  if bridge_configured; then
    echo "Infra bridge: enabled (INFRA_AMQP_URL and INFRA_API_KEY are set)."
  else
    echo "Infra bridge: NOT started (INFRA_AMQP_URL and INFRA_API_KEY are not both set in .env)." >&2
    echo "  Orders will not reach Infra until both are set; then run 'make deploy' again." >&2
  fi
  if [ -z "$key" ]; then
    echo "  Note: with INFRA_API_KEY unset the backend rejects every /infra/** call." >&2
  fi
  if [ -z "$(get WH_IMAGE_TAG)" ]; then
    echo "WH_IMAGE_TAG is not set: deploying the 'latest' images. Pin a release with WH_IMAGE_TAG=<tag> in .env." >&2
  else
    echo "WH_IMAGE_TAG=$(get WH_IMAGE_TAG): deploying that tag."
  fi
}

case "${1:-}" in
  check) check ;;
  bridge) if bridge_configured; then echo yes; else echo no; fi ;;
  *)
    echo "usage: $0 check|bridge" >&2
    exit 2
    ;;
esac
