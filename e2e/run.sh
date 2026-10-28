#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# e2e/run.sh — full-stack dry run of the backend <-> bridge <-> Infra path.
#
#   backend --Kafka(orders)--> bridge --RabbitMQ--> fake Infra
#   backend <--HTTP(/infra/**)-- bridge <--RabbitMQ-- fake Infra
#
# Spins an isolated compose project (own RabbitMQ with the MQTT plugin, never Infra's
# real broker), drives the REST API, and asserts on what happens end to end.
#
# Usage:  e2e/run.sh              # contract-conformant fake Infra (the regression test)
#         INFRA_DIR=/path/to/unzipped/delivery e2e/run.sh real
#                                 # Infra's REAL central built from their source in Docker, driven by a
#                                 # rover/camera simulator (e2e/devices/sim.py). Their code is never copied here.
# Env:    KEEP=1 leaves the stack up afterwards.   Needs: docker, curl, python3.
# ---------------------------------------------------------------------------
set -uo pipefail
cd "$(dirname "$0")/.."

MODE="${1:-fake}"
PROJECT=whe2e
API_KEY="${INFRA_API_KEY:-e2e-api-key}"
BASE=http://localhost:8080
export INFRA_API_KEY="$API_KEY"
export ADMIN_EMAIL=admin@smartwarehouse.local ADMIN_PASSWORD=changeme
DC=(docker compose -p "$PROJECT" -f docker-compose.yml -f e2e/docker-compose.e2e.yml)
[ "$MODE" = real ] && DC+=(-f e2e/docker-compose.infra-real.yml)
PASS=0; FAIL=0; B=$(mktemp)

ok()   { printf '  PASS %s\n' "$1"; PASS=$((PASS+1)); }
bad()  { printf '  FAIL %s\n' "$1"; FAIL=$((FAIL+1)); }
check(){ if [ "$2" = "$3" ]; then ok "$1 ($3)"; else bad "$1: expected '$3' got '$2'"; fi; }
jget() { # breadth-first search of $B for the first of the given keys (responses are sometimes wrapped, e.g. {"order":{...}})
  python3 -c 'import sys,json
from collections import deque
try: d=json.load(open(sys.argv[1]))
except Exception: sys.exit(0)
keys=sys.argv[2:]; q=deque([d])
while q:
    o=q.popleft()
    if isinstance(o,dict):
        for k in keys:
            if k in o and not isinstance(o[k],(dict,list)):
                print("" if o[k] is None else o[k]); sys.exit(0)
        q.extend(o.values())
    elif isinstance(o,list): q.extend(o)' "$B" "$@"; }
call() { # METHOD PATH [BODY] -> status code on stdout, body in $B
  local a=(-s -o "$B" -w '%{http_code}' -X "$1" "$BASE$2" -H "Authorization: Bearer $TOKEN")
  [ -n "${3:-}" ] && a+=(-H 'Content-Type: application/json' -d "$3")
  curl "${a[@]}"
}
wait_for() { # SECONDS DESCRIPTION CMD...
  local t=$1 d=$2; shift 2
  for _ in $(seq "$t"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done
  bad "timeout waiting for: $d"; return 1
}
status_is() { curl -s -H "Authorization: Bearer $TOKEN" "$BASE/orders/$1" | python3 -c 'import sys,json
d=json.load(sys.stdin); d=d.get("order",d); sys.exit(0 if d.get("status","").lower()==sys.argv[1] else 1)' "$2"; }
kafka_orders() { "${DC[@]}" exec -T redpanda timeout 6 rpk topic consume orders -o start --format '%v\n' 2>/dev/null; }  # whole topic, stops after 6s

cleanup() { if [ "${KEEP:-0}" != 1 ]; then "${DC[@]}" --profile infra --profile real down -v --remove-orphans >/dev/null 2>&1; fi; rm -f "$B"; }
trap cleanup EXIT

docker info >/dev/null 2>&1 || { echo "Docker daemon is not reachable (docker info failed)."; exit 2; }

echo "== starting stack (project $PROJECT) =="
PROFILES=(--profile infra); [ "$MODE" = real ] && PROFILES=(--profile infra --profile real)
"${DC[@]}" "${PROFILES[@]}" up -d --build backend bridge rabbitmq otel-collector || { echo "compose up failed"; exit 1; }
wait_for 240 "backend health" curl -sf "$BASE/actuator/health" || exit 1

TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))')
[ -n "$TOKEN" ] || { echo "login failed"; exit 1; }

echo "== arranging warehouse data =="
mkprod() { call POST /products "{\"sku\":\"E2E-$1-$RANDOM\",\"name\":\"E2E $1\",\"category\":\"HERRAMIENTAS\",\"description\":\"d\",\"price\":{\"amount_cents\":1999,\"currency\":\"USD\",\"tax_included\":true},\"max_quantity_per_order\":50,\"minimum_stock\":5,\"height\":10,\"width\":10,\"length\":10,\"weight\":1}" >/dev/null; jget id; }
P1=$(mkprod A); P2=$(mkprod B); P3=$(mkprod C)
ZC="ZE$RANDOM"
call POST /warehouse/zones "{\"zone_code\":\"$ZC\",\"max_allowed_lines\":5}" >/dev/null; ZID=$(jget id_zone)
call PATCH "/warehouse/zones/$ZID" '{"is_active":true,"max_allowed_lines":8}' >/dev/null
mkline() { call POST "/warehouse/zones/$ZID/lines" "{\"number_line\":$1,\"max_allowed_positions\":10}" >/dev/null; local id; id=$(jget id_line)
  call PATCH "/warehouse/lines/$id" '{"is_active":true,"max_allowed_positions":12}' >/dev/null; echo "$id"; }
L1=$(mkline 1); L2=$(mkline 2); L3=$(mkline 3)
# line 2 gets product A, line 1 gets product B: traversal order must be line 1 then line 2 whatever the order's items say.
mkpos() { call POST "/warehouse/lines/$1/positions" "{\"position_name\":\"$2\",\"maximum_capacity\":500,\"size_stock_to_save\":\"GRANDE\",\"qr_code\":\"$3\"}" >/dev/null; local id; id=$(jget id_position)
  call PATCH "/warehouse/positions/$id" "{\"product_id\":\"$4\",\"current_stock\":100,\"is_active\":true,\"size_stock_to_save\":\"GRANDE\"}" >/dev/null; echo "$id"; }
QR1="WH|$ZC|L1|PB"; QR2="WH|$ZC|L2|PA"
POS_B=$(mkpos "$L1" PB "$QR1" "$P2"); POS_A=$(mkpos "$L2" PA "$QR2" "$P1")
QR_MISS="WH|$ZC|L3|MISS"; POS_M=$(mkpos "$L3" PM "$QR_MISS" "$P3")
# Vehicles are not registered: the backend creates one on its first telemetry, under the UUIDv5 of
# Infra's id (same namespace as VehicleIds.java — this also checks the two implementations agree).
INFRA_VID="VHC-E2E"
VID=$(python3 -c 'import uuid,sys;print(uuid.uuid5(uuid.UUID("6f0a5d3e-2b7c-4c1e-9a58-3d1f7b2e4a90"),sys.argv[1]))' "$INFRA_VID")
[ -n "$P1" ] && [ -n "$POS_A" ] && ok "fixtures created (Infra vehicle $INFRA_VID -> backend $VID)" || { bad "fixtures"; exit 1; }

# Faults must reach both the dashboard (WebSocket) and the metrics store, under the category Infra reported.
errors_section() {
  echo "== E. vehicle errors reach the WebSocket and VictoriaMetrics =="
  local wslog; wslog=$(mktemp)
  python3 e2e/ws_listen.py "ws://localhost:8080/ws/v1/vehicles?token=$TOKEN" 45 "$wslog" &
  wait_for 10 "vehicle websocket open" test -f "$wslog" || return
  telemetry() { # STATUS [CODE MESSAGE]: what Infra puts on the 'vehicles' topic
    local err=null
    [ -n "${2:-}" ] && err="{\\\"code\\\":\\\"$2\\\",\\\"message\\\":\\\"$3\\\"}"
    curl -s -u admin_admin:admin -H 'content-type: application/json' -X POST http://localhost:15672/api/exchanges/%2F/amq.topic/publish \
      -d "{\"properties\":{\"delivery_mode\":2,\"content_type\":\"application/json\"},\"routing_key\":\"vehicles\",\"payload_encoding\":\"string\",\"payload\":\"{\\\"vehicle_id\\\":\\\"$INFRA_VID\\\",\\\"status\\\":\\\"$1\\\",\\\"battery\\\":60,\\\"last_qr\\\":null,\\\"error\\\":$err,\\\"timestamp\\\":\\\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\\\"}\"}" >/dev/null
  }
  telemetry idle; sleep 3
  telemetry error CONNECTION_LOST "Sin conexion con rover"; sleep 3
  telemetry idle; sleep 3
  telemetry error STOP_NOT_DETECTED "STOP_NOT_DETECTED: WH|E2E"; sleep 3
  telemetry idle
  for code in CONNECTION_LOST STOP_NOT_DETECTED; do
    wait_for 20 "WebSocket vehicle.error $code" grep -q "\"error_code\":\"$code\"" "$wslog" \
      && ok "WebSocket got vehicle.error $code for the backend vehicle id" \
      || continue
    grep "\"error_code\":\"$code\"" "$wslog" | grep -q "\"id\":\"$VID\"" || bad "vehicle.error $code carries a different vehicle id"
  done
  vm() { curl -s --data-urlencode "query=sum by (category) ({__name__=~\"wh_vehicle_transitions.*\",vehicle_id=\"$VID\"})" http://localhost:8428/api/v1/query; }
  for code in CONNECTION_LOST STOP_NOT_DETECTED; do
    wait_for 150 "VictoriaMetrics transitions with category=$code" bash -c "curl -s --data-urlencode 'query=sum by (category) ({__name__=~\"wh_vehicle_transitions.*\",vehicle_id=\"$VID\"})' http://localhost:8428/api/v1/query | grep -q '\"category\":\"$code\"'" \
      && ok "VictoriaMetrics has the transition counted under category $code" \
      || { echo "  last VM answer: $(vm)"; }
  done
  wait; rm -f "$wslog"
}

if [ "$MODE" = real ]; then
  echo "== Infra's real central + simulated rover/camera for vehicle $INFRA_VID =="
  export SIM_VEHICLE_ID="$INFRA_VID"
  "${DC[@]}" "${PROFILES[@]}" up -d --build infra-db central devices || { bad "could not build/run Infra's central"; exit 1; }
  wait_for 90 "central connected to the broker" bash -c "docker logs ${PROJECT}-central-1 2>&1 | grep -q 'Central conectada al broker'" && ok "central up and connected to RabbitMQ"
  vstatus() { curl -s -H "Authorization: Bearer $TOKEN" "$BASE/vehicles/$VID" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d.get("status",""))'; }
  stock() { call GET "/warehouse/positions/$1" >/dev/null; jget current_stock; }
  neworder() { call POST /orders "{\"items\":[$1],\"destination_area\":\"$ZC\",\"address\":{\"street\":\"1 Test\",\"department\":\"X\",\"floor\":\"1\",\"postal_code\":\"00001\"}}" >/dev/null; jget id; }
  item() { echo "{\"product_id\":\"$1\",\"quantity\":$2}"; }

  echo "== R1. vehicle telemetry as the real central sends it =="
  wait_for 60 "vehicle idle in backend" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"idle\"'" && ok "vehicle reached the backend as idle"
  call GET "/vehicles/$VID" >/dev/null; check "battery computed by Infra from 2.87 V / 3.3 V" "$(jget battery)" "86"

  echo "== R2. happy path: two stops, completed by the real central =="
  OID=$(neworder "$(item "$P1" 3),$(item "$P2" 4)")
  [ -n "$OID" ] && ok "order created ($OID)" || { bad "order create"; exit 1; }
  wait_for 40 "in_progress" status_is "$OID" in_progress && ok "central took the order (in_progress reached the backend through the bridge)"
  call GET "/orders/$OID" >/dev/null; check "assigned vehicle is the simulated one" "$(jget assigned_vehicle_id)" "$VID"
  wait_for 120 "completed" status_is "$OID" completed && ok "central finished both stops and reported completed"
  check "stock drained at A" "$(stock "$POS_A")" "97"; check "stock drained at B" "$(stock "$POS_B")" "96"
  wait_for 40 "vehicle idle" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"idle\"'" && ok "vehicle idle again"

  echo "== R3. stop never detected: central aborts, reports cancelled =="
  OID2=$(neworder "$(item "$P3" 2)")
  wait_for 120 "cancelled by central" status_is "$OID2" cancelled && ok "central's cancelled (STOP_NOT_DETECTED) cancelled the order in the backend"
  check "no stock drained for the aborted order" "$(stock "$POS_M")" "100"

  echo "== R4. backend cancels mid-flight: cancelled at once, the central frees the rover =="
  wait_for 60 "vehicle idle again" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"idle\"'"
  OID3=$(neworder "$(item "$P1" 1)")
  wait_for 40 "in_progress" status_is "$OID3" in_progress
  call POST "/orders/$OID3/cancel" '{"reason":"e2e real"}' >/dev/null
  status_is "$OID3" cancelled && ok "backend cancelled the in-progress order immediately" || bad "in-progress order not cancelled"
  wait_for 90 "vehicle released" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"idle\"'" && ok "central returned the rover and freed the vehicle"
  sleep 5; status_is "$OID3" cancelled && ok "order stays cancelled" || bad "cancelled order changed state"
  check "cancelled order did not drain stock" "$(stock "$POS_A")" "97"

  errors_section

  echo "== R5. rover drops off =="
  curl -s -u admin_admin:admin -H 'content-type: application/json' -X POST http://localhost:15672/api/exchanges/%2F/amq.topic/publish \
    -d '{"properties":{},"routing_key":"sim.ctl","payload_encoding":"string","payload":"rover_down"}' >/dev/null
  wait_for 40 "vehicle offline" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"offline\"'" && ok "rover/camera loss shows up as offline in the backend"

  echo "== bridge health =="
  BL=$(docker logs "${PROJECT}-bridge-1" 2>&1)
  echo "  delivered: $(echo "$BL" | grep -c 'delivered to backend')   orders published to infra: $(echo "$BL" | grep -c 'order published to infra')"
  check "bridge dropped nothing as rejected" "$(echo "$BL" | grep -c 'backend rejected message')" "0"
  check "bridge dropped nothing as unclassifiable" "$(echo "$BL" | grep -c 'dropping unclassifiable')" "0"
  echo "$BL" | grep -E 'rejected|unclassifiable|retrying' | head -5
  echo "------------------------------------------------------------"
  echo " RESULT (real central) :: $PASS passed, $FAIL failed"
  echo "------------------------------------------------------------"
  [ "$FAIL" -eq 0 ]; exit $?
fi

echo "== starting fake Infra for vehicle $INFRA_VID =="
FAKE_VEHICLE_ID="$INFRA_VID" "${DC[@]}" "${PROFILES[@]}" up -d --build fakeinfra || { bad "fakeinfra"; exit 1; }
sleep 3

echo "== 1. order -> orders topic -> RabbitMQ -> Infra -> status/telemetry over HTTP =="
call POST /orders "{\"items\":[{\"product_id\":\"$P1\",\"quantity\":3},{\"product_id\":\"$P2\",\"quantity\":4}],\"destination_area\":\"$ZC\",\"address\":{\"street\":\"1 Test\",\"department\":\"X\",\"floor\":\"1\",\"postal_code\":\"00001\"}}" >/dev/null
OID=$(jget id)
[ -n "$OID" ] && ok "order created ($OID)" || { bad "order create: $(head -c 300 "$B")"; exit 1; }

MSG=""; for _ in $(seq 10); do MSG=$(kafka_orders | grep "\"order_id\":\"$OID\"" | head -1); [ -n "$MSG" ] && break; sleep 1; done  # the topic is auto-created on first publish
STOPS=$(echo "$MSG" | python3 -c 'import sys,json;d=json.loads(sys.stdin.readline());print(",".join(d["stops"]), d["status"], "items" in d, "vehicle_id" in d)' 2>/dev/null)
check "orders topic: ordered stops, pending, no items/vehicle" "$STOPS" "$QR1,$QR2 pending False False"

wait_for 60 "order in_progress or completed" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/orders/$OID | grep -qiE '\"status\" *: *\"(in_progress|completed)\"'" && ok "bridge delivered in_progress"
wait_for 60 "order completed" status_is "$OID" completed && ok "order completed via Infra status" || true
call GET "/orders/$OID" >/dev/null
check "assigned vehicle recorded" "$(jget assigned_vehicle_id)" "$VID"
call GET "/warehouse/positions/$POS_A" >/dev/null; check "stock drained at position A (100-3)" "$(jget current_stock)" "97"
call GET "/warehouse/positions/$POS_B" >/dev/null; check "stock drained at position B (100-4)" "$(jget current_stock)" "96"
wait_for 20 "vehicle idle again" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -qi '\"status\" *: *\"idle\"'" && ok "vehicle released to idle after completion"

echo "== 2. duplicates + auth on /infra/** =="
DUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/infra/orders/$OID/status" -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' -d "{\"status\":\"completed\",\"vehicle_id\":\"$INFRA_VID\",\"timestamp\":\"2026-10-17T10:00:00Z\"}")
check "repeated 'completed' is a 200 no-op" "$DUP" "200"
call GET "/warehouse/positions/$POS_A" >/dev/null; check "stock NOT drained twice" "$(jget current_stock)" "97"
check "no api key -> 401" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/infra/orders/$OID/status" -H 'Content-Type: application/json' -d '{"status":"completed"}')" "401"
check "wrong api key -> 401" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/infra/orders/$OID/status" -H 'X-Api-Key: nope' -H 'Content-Type: application/json' -d '{"status":"completed"}')" "401"
check "unknown order -> 404" "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/infra/orders/does-not-exist/status" -H "X-Api-Key: $API_KEY" -H 'Content-Type: application/json' -d '{"status":"completed","vehicle_id":"x"}')" "404"

echo "== 3a. cancelling a pending order: cancelled at once, a late Infra 'completed' is ignored =="
# Fake Infra is stopped so the order cannot be taken before the cancel lands. Its queue is durable: it gets both messages afterwards.
"${DC[@]}" "${PROFILES[@]}" stop fakeinfra >/dev/null 2>&1
call POST /orders "{\"items\":[{\"product_id\":\"$P1\",\"quantity\":1}],\"destination_area\":\"$ZC\",\"address\":{\"street\":\"1 Test\",\"department\":\"X\",\"floor\":\"1\",\"postal_code\":\"00001\"}}" >/dev/null
OID2=$(jget id)
call POST "/orders/$OID2/cancel" '{"reason":"e2e"}' >/dev/null
status_is "$OID2" cancelled && ok "pending order cancelled immediately by backend" || bad "order not cancelled"
CANCELLED_MSG=$(kafka_orders | grep "\"order_id\":\"$OID2\"" | grep cancelled | head -1)
check "orders topic: cancelled republished" "$(echo "$CANCELLED_MSG" | python3 -c 'import sys,json;d=json.loads(sys.stdin.readline());print(d["order_id"]==sys.argv[1], d["status"], d["stops"]==[sys.argv[2]])' "$OID2" "$QR2")" "True cancelled True"
FAKE_VEHICLE_ID="$INFRA_VID" "${DC[@]}" "${PROFILES[@]}" start fakeinfra >/dev/null 2>&1
sleep 8   # fake Infra now replays the pending order (walks it to 'completed') and then the cancellation
status_is "$OID2" cancelled && ok "still cancelled after Infra's late status" || bad "cancelled order was resurrected"
call GET "/warehouse/positions/$POS_A" >/dev/null; check "cancelled order did not drain stock" "$(jget current_stock)" "97"

echo "== 3b. cancelling an in-progress order: cancelled at once and sent to Infra =="
call POST /orders "{\"items\":[{\"product_id\":\"$P1\",\"quantity\":1}],\"destination_area\":\"$ZC\",\"address\":{\"street\":\"1 Test\",\"department\":\"X\",\"floor\":\"1\",\"postal_code\":\"00001\"}}" >/dev/null
OID4=$(jget id)
wait_for 30 "order in progress" status_is "$OID4" in_progress
call POST "/orders/$OID4/cancel" '{"reason":"e2e"}' >/dev/null
status_is "$OID4" cancelled && ok "in-progress order cancelled immediately by backend" || bad "in-progress order not cancelled"
cancel_on_topic() { local all; all=$(kafka_orders); echo "$all" | grep "\"order_id\":\"$OID4\"" | grep -q cancelled; }
wait_for 20 "cancel message on orders topic" cancel_on_topic && ok "orders topic: cancellation sent to Infra"
sleep 8   # the fake keeps walking the order and reports it 'completed': the backend must ignore that
status_is "$OID4" cancelled && ok "still cancelled after Infra's late completed" || bad "cancelled in-progress order was resurrected"

errors_section

echo "== 4. bridge survives the backend being down =="
"${DC[@]}" stop backend >/dev/null 2>&1
# Publish telemetry straight into RabbitMQ (management API) while the backend is down; the bridge must hold it and retry.
curl -s -u admin_admin:admin -H 'content-type: application/json' -X POST http://localhost:15672/api/exchanges/%2F/amq.topic/publish \
  -d "{\"properties\":{\"delivery_mode\":2,\"content_type\":\"application/json\"},\"routing_key\":\"vehicles\",\"payload_encoding\":\"string\",\"payload\":\"{\\\"vehicle_id\\\":\\\"$INFRA_VID\\\",\\\"status\\\":\\\"idle\\\",\\\"battery\\\":55,\\\"last_qr\\\":null,\\\"error\\\":null,\\\"timestamp\\\":\\\"2026-10-17T10:05:00Z\\\"}\"}" >/dev/null
sleep 4
"${DC[@]}" start backend >/dev/null 2>&1
wait_for 180 "backend back" curl -sf "$BASE/actuator/health" && {
  TOKEN=$(curl -s -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))')
  wait_for 90 "queued telemetry delivered after restart (battery 55)" bash -c "curl -s -H 'Authorization: Bearer $TOKEN' $BASE/vehicles/$VID | grep -q '\"battery\" *: *55'" && ok "telemetry published while backend was down arrived after it came back"
}

echo "------------------------------------------------------------"
echo " RESULT :: $PASS passed, $FAIL failed"
echo "------------------------------------------------------------"
[ "$FAIL" -eq 0 ]
