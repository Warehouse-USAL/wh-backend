#!/usr/bin/env bash
# Tests scripts/prod-env.sh against throwaway .env files (never the real one).
# Usage: bash scripts/prod-env.test.sh
set -u

src="$(cd "$(dirname "$0")" && pwd)/prod-env.sh"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/scripts"
cp "$src" "$work/scripts/prod-env.sh"

SECRET='s3cr3t-JWT-value-0123456789-0123456789'
CORE="SPRING_DATA_MONGODB_URI=mongodb://mongodb:27017/wh
JWT_SECRET=$SECRET
MONGO_ROOT_USER=admin
MONGO_ROOT_PASSWORD=pa\$ss=w d"

fails=0
# case <name> <.env content> <want-exit> <output-substring or ""> [VAR=value ...]
# Runs `check` with a clean environment; also asserts no secret ever reaches the output.
case_() {
	name=$1 content=$2 want=$3 needle=$4
	shift 4
	if [ "$content" = NOFILE ]; then rm -f "$work/.env"; else printf '%s\n' "$content" >"$work/.env"; fi
	out=$(env -i PATH="$PATH" "$@" bash "$work/scripts/prod-env.sh" check 2>&1)
	rc=$?
	if [ "$rc" = "$want" ] && { [ -z "$needle" ] || printf '%s' "$out" | grep -qF -- "$needle"; } \
		&& ! printf '%s' "$out" | grep -qF -e "$SECRET" -e "pa\$ss"; then
		echo "ok   $name"
	else
		echo "FAIL $name: exit $rc (want $want), output:"
		echo "$out"
		fails=$((fails + 1))
	fi
}

# bridge_is <name> <.env content> <yes|no>
bridge_is() {
	printf '%s\n' "$2" >"$work/.env"
	got=$(env -i PATH="$PATH" bash "$work/scripts/prod-env.sh" bridge 2>&1)
	if [ "$got" = "$3" ]; then echo "ok   $1"; else
		echo "FAIL $1: got '$got', want '$3'"
		fails=$((fails + 1))
	fi
}

AMQP='INFRA_AMQP_URL=amqp://u:p@rabbit:5672/'
case_ all-set "$CORE
$AMQP
INFRA_API_KEY=k1" 0 "Infra bridge: enabled"
case_ infra-unset "$CORE" 0 "Infra bridge: NOT started"
case_ only-url "$CORE
$AMQP" 1 "INFRA_API_KEY is not"
case_ only-key "$CORE
INFRA_API_KEY=k1" 1 "INFRA_AMQP_URL is not"
case_ port-1883 "$CORE
INFRA_AMQP_URL=amqp://u:p@rabbit:1883/
INFRA_API_KEY=k1" 1 "port 1883"
case_ port-1883-no-slash "$CORE
INFRA_AMQP_URL=amqp://u:p@rabbit:1883
INFRA_API_KEY=k1" 1 "port 1883"
case_ port-1883-crlf "$(printf '%s\r\nINFRA_AMQP_URL=amqp://u:p@rabbit:1883\r\nINFRA_API_KEY=k1\r\n' "$CORE")" 1 "port 1883"
case_ port-18830-is-not-mqtt "$CORE
INFRA_AMQP_URL=amqp://u:p@rabbit:18830/
INFRA_API_KEY=k1" 0 "Infra bridge: enabled"
case_ missing-jwt "SPRING_DATA_MONGODB_URI=mongodb://m
MONGO_ROOT_USER=a
MONGO_ROOT_PASSWORD=b" 1 "JWT_SECRET is not set"
case_ short-jwt "SPRING_DATA_MONGODB_URI=mongodb://m
JWT_SECRET=tooshort
MONGO_ROOT_USER=a
MONGO_ROOT_PASSWORD=b" 1 "at least 32"
case_ missing-mongo-vars "JWT_SECRET=$SECRET" 1 "MONGO_ROOT_PASSWORD is not set"
case_ no-env-file NOFILE 1 "no .env file"
case_ tag-unset "$CORE" 0 "WH_IMAGE_TAG is not set"
case_ tag-set "$CORE
WH_IMAGE_TAG=v1.4.0" 0 "WH_IMAGE_TAG=v1.4.0"
case_ export-quotes-and-comments "export SPRING_DATA_MONGODB_URI=\"mongodb://m\"
export JWT_SECRET='$SECRET'   # at least 32
MONGO_ROOT_USER=a # admin
MONGO_ROOT_PASSWORD=\"b c\"
INFRA_AMQP_URL=\"amqp://u:p@rabbit:5672/\"
INFRA_API_KEY=k1 # shared" 0 "Infra bridge: enabled"
case_ commented-out-vars-are-unset "$CORE
# INFRA_AMQP_URL=amqp://u:p@rabbit:5672/
# INFRA_API_KEY=k1" 0 "Infra bridge: NOT started"
case_ environment-wins-over-env-file "$CORE" 0 "Infra bridge: enabled" INFRA_AMQP_URL=amqp://h:5672/ INFRA_API_KEY=k1
case_ last-assignment-wins "$CORE
INFRA_API_KEY=old
INFRA_AMQP_URL=amqp://u:p@rabbit:5672/
INFRA_API_KEY=" 1 "INFRA_API_KEY is not"

bridge_is bridge-yes "$CORE
$AMQP
INFRA_API_KEY=k1" yes
bridge_is bridge-no "$CORE" no

printf '%s\n' "$CORE" >"$work/.env"
if env -i PATH="$PATH" bash "$work/scripts/prod-env.sh" bogus >/dev/null 2>&1; then
	echo "FAIL usage: unknown command accepted"
	fails=$((fails + 1))
else
	echo "ok   usage"
fi

[ "$fails" -eq 0 ] || { echo "$fails failed"; exit 1; }
echo "all passed"
