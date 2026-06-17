#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# e2e-endpoints.sh — exercise EVERY backend REST endpoint against a running
# instance, in dependency order, with valid snake_case payloads + a few
# negative (authz / validation) checks. Prints a PASS/FAIL line per call and
# a summary. Used to prove the backend works end-to-end against mongo:4.4.
#
# Usage:  ADMIN_EMAIL=.. ADMIN_PASSWORD=.. ./e2e-endpoints.sh http://localhost:18080
# Needs: curl, python3.
# ---------------------------------------------------------------------------
set -uo pipefail
BASE="${1:?usage: e2e-endpoints.sh <base-url>}"
EMAIL="${ADMIN_EMAIL:?set ADMIN_EMAIL}"
PW="${ADMIN_PASSWORD:?set ADMIN_PASSWORD}"
PASS=0; FAIL=0; B=/tmp/e2e_body; TOKEN=""

# recursive JSON key finder (handles wrapped responses like {product:{id:..}})
xget(){ python3 -c '
import sys,json
def find(o,ks):
  if isinstance(o,dict):
    for k,v in o.items():
      if k in ks and isinstance(v,(str,int,float)): return str(v)
    for v in o.values():
      r=find(v,ks)
      if r is not None: return r
  elif isinstance(o,list):
    for v in o:
      r=find(v,ks)
      if r is not None: return r
  return None
try: d=json.load(open("'"$B"'"))
except Exception: sys.exit(0)
print(find(d,set(sys.argv[1:])) or "")' "$@"; }

# call NAME METHOD PATH EXPECTED_CODES [JSON_BODY] [BEARER]
call(){
  local name=$1 method=$2 path=$3 expect=$4 data=${5:-} tok=${6:-$TOKEN}
  local a=(-s -o "$B" -w '%{http_code}' -X "$method" "$BASE$path")
  [ -n "$tok" ] && a+=(-H "Authorization: Bearer $tok")
  [ -n "$data" ] && a+=(-H "Content-Type: application/json" -d "$data")
  local code; code=$(curl "${a[@]}")
  if echo " $expect " | grep -q " $code "; then
    printf '  PASS [%s] %-34s %s %s\n' "$code" "$name" "$method" "$path"; PASS=$((PASS+1))
  else
    printf '  FAIL [%s want %s] %-28s %s %s :: %s\n' "$code" "$expect" "$name" "$method" "$path" "$(head -c 220 "$B" | tr '\n' ' ')"; FAIL=$((FAIL+1))
  fi
}

echo "================ E2E endpoint sweep :: $BASE ================"

# ---- public ----
call "health"            GET  /actuator/health           200 "" ""
call "login-badpass"     POST /auth/login                401 "{\"email\":\"$EMAIL\",\"password\":\"definitely-wrong\"}" ""
curl -s -o "$B" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$EMAIL\",\"password\":\"$PW\"}" >/dev/null
TOKEN=$(xget token)
if [ -n "$TOKEN" ]; then echo "  PASS [200] login (token acquired)               POST /auth/login"; PASS=$((PASS+1)); else echo "  FAIL login produced no token :: $(head -c200 $B)"; FAIL=$((FAIL+1)); echo "ABORT: cannot continue without token"; exit 1; fi
call "no-token-401"      GET  /products                   401 "" "none"

# ---- products ----
SKU="E2E-$RANDOM"
call "product-create"    POST /products 201 "{\"sku\":\"$SKU\",\"name\":\"E2E Widget\",\"category\":\"HERRAMIENTAS\",\"description\":\"d\",\"price\":{\"amount_cents\":1999,\"currency\":\"USD\",\"tax_included\":true},\"max_quantity_per_order\":50,\"minimum_stock\":5}"
PID=$(xget id)
call "product-list"      GET  /products                   200
call "product-get"       GET  "/products/$PID"            200
call "product-patch"     PATCH "/products/$PID"           200 "{\"description\":\"updated\",\"minimum_stock\":10}"
call "product-location"  GET  "/products/$PID/location"   200

# ---- warehouse: zone -> line -> position ----
ZC="Z$RANDOM"
call "zone-create"       POST /warehouse/zones            201 "{\"zone_code\":\"$ZC\",\"max_allowed_lines\":5}"
ZID=$(xget id_zone idZone id)
call "zone-list"         GET  /warehouse/zones            200
call "zone-activate"     PATCH "/warehouse/zones/$ZID"    200 "{\"is_active\":true,\"max_allowed_lines\":8}"

call "line-create"       POST "/warehouse/zones/$ZID/lines" 201 "{\"number_line\":7,\"max_allowed_positions\":10}"
LID=$(xget id_line idLine id)
call "line-list"         GET  "/warehouse/zones/$ZID/lines" 200
call "line-activate"     PATCH "/warehouse/lines/$LID"    200 "{\"is_active\":true,\"max_allowed_positions\":12}"

call "pos-create"        POST "/warehouse/lines/$LID/positions" 201 "{\"position_name\":\"PE2E\",\"maximum_capacity\":500,\"size_stock_to_save\":\"GRANDE\"}"
POSID=$(xget id_position idPosition id)
call "pos-list"          GET  "/warehouse/lines/$LID/positions" 200
call "pos-get"           GET  "/warehouse/positions/$POSID" 200
# assign product + stock to the position (gives the product available stock for ordering)
call "pos-assign-stock"  PATCH "/warehouse/positions/$POSID" 200 "{\"product_id\":\"$PID\",\"current_stock\":100,\"is_active\":true,\"size_stock_to_save\":\"GRANDE\"}"

# ---- vehicles ----
call "vehicle-create"    POST /vehicles                   201 "{\"name\":\"E2E-Truck\"}"
VID=$(xget id)
call "vehicle-list"      GET  /vehicles                   200
call "vehicle-get"       GET  "/vehicles/$VID"            200

# ---- users (+ negative authz with a low-priv user) ----
UEMAIL="op$RANDOM@e2e.local"
call "user-create"       POST /users 201 "{\"email\":\"$UEMAIL\",\"name\":\"E2E Op\",\"role\":\"OPERATOR\",\"initial_password\":\"OpPass123\"}"
USERID=$(xget id)
call "user-list"         GET  /users                      200
call "user-get"          GET  "/users/$USERID"               200
call "user-patch"        PATCH "/users/$USERID"              200 "{\"name\":\"E2E Op Renamed\"}"
call "user-reset-pw"     POST "/users/$USERID/reset-password" 204 "{\"new_password\":\"OpPass456\"}"
# log in as the operator, then self change-password + verify a 403 on an admin-only route
curl -s -o "$B" -X POST "$BASE/auth/login" -H 'Content-Type: application/json' -d "{\"email\":\"$UEMAIL\",\"password\":\"OpPass456\"}" >/dev/null
OPTOK=$(xget token)
if [ -n "$OPTOK" ]; then
  call "self-change-pw"    POST /users/me/change-password   200 "{\"current_password\":\"OpPass456\",\"new_password\":\"OpPass789\"}" "$OPTOK"
  call "operator-403"      POST /vehicles                   403 "{\"name\":\"nope\"}" "$OPTOK"
else
  echo "  FAIL operator login produced no token (cannot test self-change-pw / 403)"; FAIL=$((FAIL+1))
fi

# ---- orders (needs product with stock) ----
call "order-create"      POST /orders 201 "{\"items\":[{\"product_id\":\"$PID\",\"quantity\":5}],\"destination_area\":\"$ZC\",\"address\":{\"street\":\"1 Test\",\"department\":\"X\",\"floor\":\"1\",\"postal_code\":\"00001\"}}"
OID=$(xget id)
call "order-list"        GET  /orders                     200
call "order-get"         GET  "/orders/$OID"              200
call "order-cancel"      POST "/orders/$OID/cancel"       "200 201" "{\"reason\":\"e2e test\"}"

# ---- files (multipart upload -> public get -> delete) ----
printf "%s" "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M8AAAMBAQDJ/pLvAAAAAElFTkSuQmCC" | base64 -d > /tmp/e2e_up.png
UPCODE=$(curl -s -o "$B" -w '%{http_code}' -X POST "$BASE/api/v1/files/upload" -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/e2e_up.png;type=image/png")
if echo " 200 201 " | grep -q " $UPCODE "; then echo "  PASS [$UPCODE] file-upload                       POST /api/v1/files/upload"; PASS=$((PASS+1)); else echo "  FAIL [$UPCODE] file-upload :: $(head -c220 $B|tr '\n' ' ')"; FAIL=$((FAIL+1)); fi
FKEY=$(xget key)   # e.g. "path/filename"
if [ -n "$FKEY" ]; then
  call "file-get"          GET    "/api/v1/files/$FKEY"   200
  call "file-delete"       DELETE "/api/v1/files/$FKEY"   204
fi

# ---- cleanup deletes (reverse dependency order) ----
call "pos-delete"        DELETE "/warehouse/positions/$POSID" 204
call "line-delete"       DELETE "/warehouse/lines/$LID"   204
call "zone-delete"       DELETE "/warehouse/zones/$ZID"   204
call "product-delete"    DELETE "/products/$PID"          204

echo "------------------------------------------------------------"
echo " RESULT :: $PASS passed, $FAIL failed"
echo "------------------------------------------------------------"
[ "$FAIL" -eq 0 ]
