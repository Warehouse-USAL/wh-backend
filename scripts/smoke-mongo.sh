#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# smoke-mongo.sh — prove a MongoDB image is a functional drop-in for wh-backend.
#
# Why: the prod box (HP ProLiant) has no AVX, so mongo:5.0+ (incl. our mongo:7)
# dies with "Illegal instruction". We want to pin mongo:4.4 (last no-AVX line).
# Before trusting that, this script verifies — empirically, on a real daemon —
# that 4.4 supports everything the backend actually uses:
#   1. which shell the image ships (mongosh vs legacy mongo) -> healthcheck impact
#   2. replica set initiation (transactions require a replica set)
#   3. the ProductService stock aggregation ($match/$unwind/$group/$sum)
#   4. multi-document transactions: commit lands, abort rolls back (@Transactional)
#   5. auth/root-user creation via the official entrypoint (MONGO_INITDB_ROOT_*)
#
# Run it against the candidate AND the current image to prove parity:
#   ./scripts/smoke-mongo.sh mongo:4.4
#   ./scripts/smoke-mongo.sh mongo:7
#
# Exit code 0 = all checks passed.
# ---------------------------------------------------------------------------
set -uo pipefail

IMG="${1:?usage: smoke-mongo.sh <mongo-image:tag>   e.g. mongo:4.4}"
SFX="$$"
C_RS="smoke-rs-$SFX"
C_AUTH="smoke-auth-$SFX"
PASS=0; FAIL=0
ok()  { echo "  PASS  $1"; PASS=$((PASS+1)); }
bad() { echo "  FAIL  $1"; FAIL=$((FAIL+1)); }
cleanup() { docker rm -f "$C_RS" "$C_AUTH" >/dev/null 2>&1 || true; }
trap cleanup EXIT

# run a JS snippet in container $1 via the detected shell; trailing args appended
sh_eval() { local c="$1" js="$2"; shift 2; docker exec "$c" "$SHELL_BIN" --quiet "$@" --eval "$js" 2>/dev/null | tr -d '\r'; }
wait_ping() { local c="$1"; shift; for _ in $(seq 1 40); do [ "$(sh_eval "$c" 'try{db.runCommand({ping:1}).ok}catch(e){0}' "$@")" = "1" ] && return 0; sleep 1; done; return 1; }

echo "================================================================"
echo " MongoDB drop-in smoke test :: $IMG"
echo "================================================================"
docker pull "$IMG" >/dev/null 2>&1 || { echo "ERROR: could not pull $IMG"; exit 2; }

# --- 0. which shell does the image ship? -----------------------------------
if docker run --rm --entrypoint sh "$IMG" -c 'command -v mongosh' >/dev/null 2>&1; then
  SHELL_BIN=mongosh
  ok "image ships 'mongosh' (compose healthcheck may use mongosh)"
else
  SHELL_BIN=mongo
  echo "  NOTE  image ships only the legacy 'mongo' shell — NOT mongosh"
  echo "        => the docker-compose healthcheck MUST call 'mongo', not 'mongosh'"
  ok "legacy 'mongo' shell present (healthcheck must be adjusted accordingly)"
fi
echo "  (using '$SHELL_BIN' for the rest of this run)"

# --- 1. replica set + aggregation + transactions (data plane, no auth) ------
docker run -d --name "$C_RS" "$IMG" --replSet rs0 --bind_ip_all >/dev/null
if wait_ping "$C_RS"; then ok "mongod started and answers ping"; else bad "mongod never answered ping"; echo; exit 1; fi

# init replica set. NOTE: the legacy 'mongo' shell (4.4) RETURNS {ok:0} on an
# uninitialized node instead of THROWING like mongosh does — so we must test the
# .ok value, not rely on a catch. (This is exactly why the compose healthcheck
# had to be rewritten for 4.4.)
sh_eval "$C_RS" 'var s;try{s=rs.status().ok}catch(e){s=0}; if(s!==1){rs.initiate({_id:"rs0",members:[{_id:0,host:"localhost:27017"}]})}' >/dev/null
PRIMARY=no
for _ in $(seq 1 40); do
  [ "$(sh_eval "$C_RS" 'try{(rs.isMaster().ismaster)?1:0}catch(e){0}')" = "1" ] && { PRIMARY=yes; break; }
  sleep 1
done
[ "$PRIMARY" = yes ] && ok "replica set rs0 reached PRIMARY" || bad "replica set never became PRIMARY"

# the exact ProductService aggregation: stock for a product across open orders
AGG_JS='db=db.getSiblingDB("smoke");
db.orders.deleteMany({});
db.orders.insertMany([
 {status:"PENDING",     items:[{productId:"P1",quantity:3},{productId:"P2",quantity:5}]},
 {status:"IN_PROGRESS", items:[{productId:"P1",quantity:7}]},
 {status:"DONE",        items:[{productId:"P1",quantity:100}]}
]);
var r=db.orders.aggregate([
 {$match:{status:{$in:["PENDING","IN_PROGRESS"]}}},
 {$unwind:"$items"},
 {$match:{"items.productId":"P1"}},
 {$group:{_id:null,total:{$sum:"$items.quantity"}}}
]).toArray();
print("AGG="+(r.length?r[0].total:0));'
AGG=$(sh_eval "$C_RS" "$AGG_JS" | sed -n 's/^AGG=//p')
[ "$AGG" = "10" ] && ok "stock aggregation \$match/\$unwind/\$group/\$sum => $AGG (expected 10)" \
                  || bad "aggregation returned '$AGG' (expected 10)"

# multi-document transaction: commit must persist both writes
TX_JS='var db0=db.getSiblingDB("smoke");
var s=db.getMongo().startSession(); var d=s.getDatabase("smoke");
s.startTransaction(); d.acct.insertOne({_id:"c1"}); d.audit.insertOne({_id:"c1"}); s.commitTransaction(); s.endSession();
print("COMMIT="+(db0.acct.countDocuments({_id:"c1"})+db0.audit.countDocuments({_id:"c1"})));'
COMMIT=$(sh_eval "$C_RS" "$TX_JS" | sed -n 's/^COMMIT=//p')
[ "$COMMIT" = "2" ] && ok "transaction COMMIT persisted both writes (=$COMMIT)" \
                    || bad "transaction commit landed '$COMMIT' docs (expected 2)"

# multi-document transaction: abort must roll back
TXA_JS='var db0=db.getSiblingDB("smoke");
var s=db.getMongo().startSession(); var d=s.getDatabase("smoke");
s.startTransaction(); d.acct.insertOne({_id:"a1"}); s.abortTransaction(); s.endSession();
print("ABORT="+db0.acct.countDocuments({_id:"a1"}));'
ABORT=$(sh_eval "$C_RS" "$TXA_JS" | sed -n 's/^ABORT=//p')
[ "$ABORT" = "0" ] && ok "transaction ABORT rolled back cleanly (=$ABORT leftover)" \
                   || bad "transaction abort left '$ABORT' docs (expected 0)"

# --- 2. auth / root-user creation via the official entrypoint ---------------
docker run -d --name "$C_AUTH" \
  -e MONGO_INITDB_ROOT_USERNAME=root -e MONGO_INITDB_ROOT_PASSWORD=s3cret "$IMG" >/dev/null
if wait_ping "$C_AUTH" -u root -p s3cret --authenticationDatabase admin; then
  ok "entrypoint created root user; authenticated connection works"
else
  bad "could not authenticate as the entrypoint-created root user"
fi
WRONG=$(docker exec "$C_AUTH" "$SHELL_BIN" --quiet -u root -p WRONGPASS \
        --authenticationDatabase admin --eval 'db.runCommand({ping:1}).ok' 2>&1 | tr -d '\r')
echo "$WRONG" | grep -qiE 'auth|fail|denied|18' && ok "wrong password is rejected (auth enforced)" \
                                                || bad "wrong password NOT rejected: $WRONG"

echo "----------------------------------------------------------------"
echo " $IMG :: $PASS passed, $FAIL failed"
echo "----------------------------------------------------------------"
[ "$FAIL" -eq 0 ]
