#!/usr/bin/env bash
#
# Acceptance test for the dashboard API, driven exactly as Grupo 3's frontend would drive it:
# over HTTP, authenticated as the read-only DASHBOARD role, with no access to the database.
#
# It walks the twenty-one metrics the dashboard team asked for, plus discovery and the security
# boundary. Every assertion is on the HTTP response alone — if this passes, the API is usable by
# a consumer holding nothing but a token and the catalogue.
#
#   ./scripts/blackbox-dashboard.sh                 # against http://localhost:8080
#   BASE_URL=http://localhost:8090 ./scripts/blackbox-dashboard.sh
#
# Requires a stack seeded with SEED_DEMO=true.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
EMAIL="${DASHBOARD_EMAIL:-dashboard@smartwarehouse.local}"
PASSWORD="${DASHBOARD_PASSWORD:-Demo1234!}"

PASS=0
FAIL=0
TOKEN=""

green() { printf '\033[32m%s\033[0m' "$1"; }
red()   { printf '\033[31m%s\033[0m' "$1"; }
dim()   { printf '\033[2m%s\033[0m' "$1"; }

# check <label> <python-expression-over-`d`> <json>
# The expression receives the parsed response as `d` and must evaluate truthy.
check() {
  local label="$1" expr="$2" json="$3"
  local verdict
  verdict=$(printf '%s' "$json" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
except Exception as e:
    print('BADJSON ' + str(e)[:80]); raise SystemExit
try:
    print('OK' if ($expr) else 'NO ' + json.dumps(d)[:150])
except Exception as e:
    print('ERR ' + type(e).__name__ + ' ' + str(e)[:80] + ' :: ' + json.dumps(d)[:120])
" 2>/dev/null)
  if [ "${verdict:0:2}" = "OK" ]; then
    PASS=$((PASS + 1)); printf '  %s %s\n' "$(green ✓)" "$label"
  else
    FAIL=$((FAIL + 1)); printf '  %s %s\n      %s\n' "$(red ✗)" "$label" "$(dim "$verdict")"
  fi
}

q()  { curl -s -X POST "$BASE_URL/query/$1"   -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$2"; }
mq() { curl -s -X POST "$BASE_URL/metrics/query" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$1"; }
g()  { curl -s "$BASE_URL$1" -H "Authorization: Bearer $TOKEN"; }

# Windows. The business data spans about three weeks; the seeded telemetry spans seven days.
FROM=$(python3 -c "import datetime as t;print((t.datetime.now(t.UTC)-t.timedelta(days=60)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
TO=$(python3   -c "import datetime as t;print((t.datetime.now(t.UTC)+t.timedelta(days=1)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
MFROM=$(python3 -c "import datetime as t;print((t.datetime.now(t.UTC)-t.timedelta(days=6)).strftime('%Y-%m-%dT%H:%M:%SZ'))")
MTO=$(python3   -c "import datetime as t;print(t.datetime.now(t.UTC).strftime('%Y-%m-%dT%H:%M:%SZ'))")
W="{\"field\":\"created_at\",\"op\":\"gte\",\"value\":\"$FROM\"},{\"field\":\"created_at\",\"op\":\"lt\",\"value\":\"$TO\"}"

echo
echo "Dashboard blackbox — $BASE_URL"
echo "  business window $FROM .. $TO"
echo "  telemetry window $MFROM .. $MTO"

# ── Discovery ────────────────────────────────────────────────────────────────
echo
echo "Discovery"
LOGIN=$(curl -s -X POST "$BASE_URL/auth/login" -H 'Content-Type: application/json' \
        -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
check "logs in as the DASHBOARD role" "d['user']['role']=='DASHBOARD' and len(d['token'])>20" "$LOGIN"
TOKEN=$(printf '%s' "$LOGIN" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("token",""))' 2>/dev/null)
if [ -z "$TOKEN" ]; then echo; red "cannot authenticate — is the stack up and seeded?"; echo; exit 1; fi

# The web server accepts requests before ApplicationRunners finish, so a healthy /actuator/health
# does not mean the demo seed has landed. Wait for it rather than racing it and reporting a
# failure that is really a startup ordering artefact.
printf '  '; dim "waiting for seeded telemetry to become queryable"; printf '\n'
for _ in $(seq 1 30); do
  READY=$(mq "{\"metric\":\"wh.vehicle.state\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"6h\",\"agg\":\"count\",\"filters\":{\"state\":\"BUSY\"}}" \
    | python3 -c "import sys,json
try:
    d=json.load(sys.stdin); print('yes' if d.get('series') and d['series'][0].get('points') else 'no')
except Exception: print('no')" 2>/dev/null)
  [ "$READY" = "yes" ] && break
  sleep 2
done

check "entity catalogue lists what a dashboard needs" \
  "{'orders','products','vehicles','positions'} <= {e['name'] for e in d['entities']}" "$(g /query/catalog)"
check "entity catalogue hides the user table" \
  "'users' not in {e['name'] for e in d['entities']}" "$(g /query/catalog)"
check "metric catalogue is self-describing" \
  "{'wh.vehicle.battery','wh.vehicle.state','wh.vehicle.transitions'} <= {m['name'] for m in d['metrics']} and all(m['permitted_aggregations'] for m in d['metrics'])" \
  "$(g /metrics/catalog)"

# ── Rover metrics, via the metrics store ─────────────────────────────────────
echo
echo "Fleet metrics (OTel → VictoriaMetrics)"
check "1. histórico de fallas por rover" \
  "len(d['series'])>0 and any(p[1]>0 for s in d['series'] for p in s['points'])" \
  "$(mq "{\"metric\":\"wh.vehicle.transitions\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"1h\",\"agg\":\"increase\",\"group_by\":[\"vehicle_id\"],\"filters\":{\"to\":\"ERROR\"}}")"

check "2. pareto de fallas por categoría" \
  "len(d['series'])>0 and all('category' in s['labels'] for s in d['series'])" \
  "$(mq "{\"metric\":\"wh.vehicle.transitions\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"6h\",\"agg\":\"increase\",\"group_by\":[\"category\"],\"filters\":{\"to\":\"ERROR\"}}")"

check "3. MTBF input — failures over the window" \
  "len(d['series'])>0 and sum(p[1] for s in d['series'] for p in s['points'])>0" \
  "$(mq "{\"metric\":\"wh.vehicle.transitions\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"6h\",\"agg\":\"increase\",\"filters\":{\"to\":\"ERROR\"}}")"

check "4. MTTR input — share of time spent in ERROR" \
  "len(d['series'])>0 and all(0<=p[1]<=1 for s in d['series'] for p in s['points'])" \
  "$(mq "{\"metric\":\"wh.vehicle.state\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"1h\",\"agg\":\"avg\",\"group_by\":[\"vehicle_id\"],\"filters\":{\"state\":\"ERROR\"}}")"

check "5. rovers activos simultáneamente" \
  "len(d['series'])==1 and len(d['series'][0]['points'])>0 and all(0<=p[1]<=6 for p in d['series'][0]['points'])" \
  "$(mq "{\"metric\":\"wh.vehicle.state\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"1h\",\"agg\":\"count\",\"filters\":{\"state\":\"BUSY\"}}")"

check "   battery history is chartable" \
  "len(d['series'])>0 and all(0<=p[1]<=100 for s in d['series'] for p in s['points'])" \
  "$(mq "{\"metric\":\"wh.vehicle.battery\",\"from\":\"$MFROM\",\"to\":\"$MTO\",\"step\":\"1h\",\"agg\":\"avg\",\"group_by\":[\"vehicle_id\"]}")"

# ── Business metrics, via the query API ──────────────────────────────────────
echo
echo "Business metrics (MongoDB aggregation)"
check "6. top SKUs" \
  "len(d['items'])>0 and all('sku' in i and i['units']>0 for i in d['items']) and d['items']==sorted(d['items'],key=lambda i:-i['units'])" \
  "$(q orders "{\"filters\":[$W],\"unwind\":\"items\",\"group_by\":[{\"field\":\"items.sku\",\"as\":\"sku\"}],\"aggregates\":[{\"op\":\"sum\",\"field\":\"items.quantity\",\"as\":\"units\"}],\"sort\":[{\"field\":\"units\",\"dir\":\"desc\"}],\"size\":10}")"

ORDERS_BY_STATUS=$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"orders\"}]}")
check "7. pedidos completados y totales del período" \
  "sum(i['orders'] for i in d['items'])>0 and any(i['status']=='COMPLETED' for i in d['items'])" "$ORDERS_BY_STATUS"
check "8. % de cumplimiento is derivable client-side" \
  "0 <= sum(i['orders'] for i in d['items'] if i['status']=='COMPLETED')/sum(i['orders'] for i in d['items']) <= 1" "$ORDERS_BY_STATUS"

check "9. pedidos por hora, completados vs cancelados" \
  "len(d['items'])>0 and all(len(i['hour'])==19 and i['hour'].endswith(':00:00') for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"created_at\",\"bucket\":\"hour\",\"as\":\"hour\"},{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"orders\"}],\"size\":200}")"

check "10. tasa de cumplimiento por día" \
  "len(d['items'])>0 and all(len(i['day'])==10 for i in d['items']) and [i['day'] for i in d['items']]==sorted(i['day'] for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"created_at\",\"bucket\":\"day\",\"as\":\"day\"},{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"orders\"}],\"size\":200}")"

check "11. productividad por rover" \
  "len(d['items'])>0 and all(i['orders']>0 for i in d['items'])" \
  "$(q orders "{\"filters\":[$W,{\"field\":\"assigned_vehicle_id\",\"op\":\"exists\",\"value\":true}],\"group_by\":[{\"field\":\"assigned_vehicle_id\",\"as\":\"vehicle\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"orders\"}]}")"

check "12. cycle time promedio" \
  "any(i.get('avg_cycle_ms') for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"avg\",\"field\":\"cycle_time_ms\",\"as\":\"avg_cycle_ms\"}]}")"

check "13. tiempo hasta asignación de vehículo" \
  "any(i.get('avg_assign_ms') is not None for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"avg\",\"field\":\"assignment_latency_ms\",\"as\":\"avg_assign_ms\"}]}")"

check "14. SLA compliance — caller picks the threshold" \
  "sum(i['n'] for i in d['items'])>=0" \
  "$(q orders "{\"filters\":[$W,{\"field\":\"cycle_time_ms\",\"op\":\"lte\",\"value\":28800000}],\"group_by\":[{\"field\":\"status\",\"as\":\"status\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}]}")"

check "15. eficiencia de picking — lines per order per day" \
  "len(d['items'])>0 and all(i['lines']>0 for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"unwind\":\"items\",\"group_by\":[{\"field\":\"created_at\",\"bucket\":\"day\",\"as\":\"day\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"lines\"},{\"op\":\"sum\",\"field\":\"items.quantity\",\"as\":\"units\"}],\"size\":100}")"

# Buckets must follow the warehouse's working day, not UTC. Asking the same question in two
# zones and getting the same answer would mean the timezone is being ignored.
LOCAL_H=$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"created_at\",\"bucket\":\"hour\",\"as\":\"hour\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}],\"size\":200,\"timezone\":\"America/Argentina/Buenos_Aires\"}")
UTC_H=$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"created_at\",\"bucket\":\"hour\",\"as\":\"hour\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}],\"size\":200,\"timezone\":\"UTC\"}")
BOTH=$(python3 -c "
import json,sys
loc=json.loads(sys.argv[1])['items']; utc=json.loads(sys.argv[2])['items']
print(json.dumps({'shifted': [i['hour'] for i in loc] != [i['hour'] for i in utc],
                  'same_total': sum(i['n'] for i in loc)==sum(i['n'] for i in utc)}))
" "$LOCAL_H" "$UTC_H" 2>/dev/null)
check "   hour buckets follow the requested timezone" "d['shifted'] and d['same_total']" "$BOTH"

# ── Inventory ────────────────────────────────────────────────────────────────
echo
echo "Inventory and demand"
check "16. demanda diaria por SKU" \
  "len(d['items'])>0 and all('sku' in i and 'day' in i for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"unwind\":\"items\",\"group_by\":[{\"field\":\"items.sku\",\"as\":\"sku\"},{\"field\":\"created_at\",\"bucket\":\"day\",\"as\":\"day\"}],\"aggregates\":[{\"op\":\"sum\",\"field\":\"items.quantity\",\"as\":\"units\"}],\"size\":500}")"

check "17. última vez que se pidió cada SKU" \
  "len(d['items'])>0 and all(i['last_ordered'].endswith('Z') for i in d['items'])" \
  "$(q orders "{\"filters\":[$W],\"unwind\":\"items\",\"group_by\":[{\"field\":\"items.sku\",\"as\":\"sku\"}],\"aggregates\":[{\"op\":\"max\",\"field\":\"created_at\",\"as\":\"last_ordered\"}],\"size\":50}")"

check "18. stock actual por producto — NO date filter needed" \
  "len(d['items'])>0 and all(i['on_hand']>=0 for i in d['items'])" \
  "$(q positions '{"group_by":[{"field":"product_id","as":"product_id"}],"aggregates":[{"op":"sum","field":"current_stock","as":"on_hand"}],"sort":[{"field":"on_hand","dir":"desc"}],"size":50}')"

check "19. top rotación — demand ranking" \
  "len(d['items'])>1 and d['items'][0]['units']>=d['items'][-1]['units']" \
  "$(q orders "{\"filters\":[$W],\"unwind\":\"items\",\"group_by\":[{\"field\":\"items.sku\",\"as\":\"sku\"}],\"aggregates\":[{\"op\":\"sum\",\"field\":\"items.quantity\",\"as\":\"units\"}],\"sort\":[{\"field\":\"units\",\"dir\":\"desc\"}],\"size\":20}")"

check "20. minimum stock per product, for reorder rules" \
  "len(d['items'])>0" \
  "$(q products '{"group_by":[{"field":"category","as":"category"}],"aggregates":[{"op":"sum","field":"minimum_stock","as":"min_stock"},{"op":"count","as":"products"}]}')"

check "21. capacity utilisation per zone" \
  "len(d['items'])>0 and all(i['capacity']>0 for i in d['items'])" \
  "$(q positions '{"group_by":[{"field":"id_zone","as":"zone"}],"aggregates":[{"op":"sum","field":"current_stock","as":"stock"},{"op":"sum","field":"maximum_capacity","as":"capacity"}]}')"

# ── Security boundary ────────────────────────────────────────────────────────
echo
echo "Security boundary"
check "the user table is not reachable" "d['error']['code']=='UNKNOWN_ENTITY'" \
  "$(q users "{\"filters\":[$W],\"group_by\":[{\"field\":\"role\",\"as\":\"r\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}]}")"
check "password_hash cannot be grouped" "d['error']['code']=='UNKNOWN_FIELD'" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"password_hash\",\"as\":\"h\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}]}")"
check "an unbounded order aggregation is refused" "d['error']['code']=='UNBOUNDED_RANGE'" \
  "$(q orders '{"group_by":[{"field":"status","as":"s"}],"aggregates":[{"op":"count","as":"n"}]}')"
check "an over-wide window is refused" "d['error']['code']=='QUERY_TOO_BROAD'" \
  "$(q orders '{"filters":[{"field":"created_at","op":"gte","value":"2020-01-01T00:00:00Z"}],"group_by":[{"field":"status","as":"s"}],"aggregates":[{"op":"count","as":"n"}]}')"
check "a hostile output alias is refused" "d['error']['code']=='INVALID_ALIAS'" \
  "$(q orders "{\"filters\":[$W],\"group_by\":[{\"field\":\"status\",\"as\":\"\$where\"}],\"aggregates\":[{\"op\":\"count\",\"as\":\"n\"}]}")"
check "the dashboard cannot write" \
  "d.get('error',{}).get('code') in ('ACCESS_DENIED','FORBIDDEN') or 'error' in d" \
  "$(curl -s -X POST "$BASE_URL/products" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"sku":"HACK-1","name":"x","category":"OTROS"}')"

echo
TOTAL=$((PASS + FAIL))
if [ "$FAIL" -eq 0 ]; then
  printf '%s  %s/%s checks passed\n\n' "$(green PASS)" "$PASS" "$TOTAL"; exit 0
else
  printf '%s  %s/%s checks failed\n\n' "$(red FAIL)" "$FAIL" "$TOTAL"; exit 1
fi
