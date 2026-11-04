// Query plans of the hot read paths, run in the mongo shell of the stack (MongoDB 4.4):
//   docker compose exec -T mongodb mongo --quiet smartwarehouse /load/explain.js
// For each query: winning plan stages, index used, keys/docs examined versus returned, and time.
var d = db.getSiblingDB('smartwarehouse');
var now = new Date();
var since = new Date(now.getTime() - 60 * 86400000);
var from730 = new Date(now.getTime() - 730 * 86400000);
var sampleProduct = d.orders.findOne({}, { 'items.productId': 1 }).items[0].productId;
var sampleUser = d.orders.findOne({}, { requestedByUserId: 1 }).requestedByUserId;

function stages(plan) {
  var out = [];
  (function walk(p) {
    if (!p) return;
    out.push(p.stage + (p.indexName ? '(' + p.indexName + ')' : ''));
    if (p.inputStage) walk(p.inputStage);
    if (p.inputStages) p.inputStages.forEach(walk);
    if (p.shards) p.shards.forEach(function (s) { walk(s.winningPlan); });
  })(plan);
  return out.join(' > ');
}

function show(name, explained) {
  var es = explained.executionStats || explained.stages && explained.stages[0].$cursor.executionStats;
  var plan = explained.queryPlanner ? explained.queryPlanner.winningPlan : explained.stages[0].$cursor.queryPlanner.winningPlan;
  print(JSON.stringify({
    query: name, plan: stages(plan), returned: es.nReturned, keys_examined: es.totalKeysExamined,
    docs_examined: es.totalDocsExamined, ms: es.executionTimeMillis,
  }));
}

var total = d.orders.count();
print(JSON.stringify({ orders: total, indexes: d.orders.getIndexes().map(function (i) { return i.name; }) }));

show('GET /orders page 0 (no sort, skip 0 limit 20)', d.orders.find({}).skip(0).limit(20).explain('executionStats'));
show('GET /orders deep page (no sort, skip 20000 limit 20)', d.orders.find({}).skip(20000).limit(20).explain('executionStats'));
show('GET /orders?status=COMPLETED page 5', d.orders.find({ status: 'COMPLETED' }).skip(100).limit(20).explain('executionStats'));
show('GET /orders?status=COMPLETED count', d.runCommand({ explain: { count: 'orders', query: { status: 'COMPLETED' } }, verbosity: 'executionStats' }));
show('GET /orders?from&to (createdAt range) skip 0', d.orders.find({ createdAt: { $gte: since, $lte: now } }).skip(0).limit(20).explain('executionStats'));
show('POST /query/orders status=COMPLETED sort createdAt desc', d.orders.find({ status: 'COMPLETED' }).sort({ createdAt: -1 }).skip(400).limit(20).explain('executionStats'));
show('POST /query/orders sort createdAt desc (no filter)', d.orders.find({}).sort({ createdAt: -1 }).skip(400).limit(20).explain('executionStats'));
show('POST /query/orders requested_by_user_id eq sort createdAt desc', d.orders.find({ requestedByUserId: sampleUser }).sort({ createdAt: -1 }).limit(20).explain('executionStats'));
show('reserved stock: active orders of a product', d.orders.find({ status: { $in: ['PENDING', 'IN_PROGRESS'] }, 'items.productId': sampleProduct }).explain('executionStats'));
show('suggestion-conversion (730 days, status != CANCELLED)', d.orders.find({ status: { $ne: 'CANCELLED' }, createdAt: { $gte: from730, $lt: now } }, { status: 1, createdAt: 1, 'items.productId': 1, 'items.quantity': 1, 'items.fromRecommendation': 1 }).explain('executionStats'));
show('restock-suggestions orders (60 days, product in [..])', d.orders.find({ status: { $ne: 'CANCELLED' }, createdAt: { $gte: since }, 'items.productId': { $in: [sampleProduct] } }).explain('executionStats'));
show('orders by assigned vehicle', d.orders.find({ assignedVehicleId: 'x' }).limit(20).explain('executionStats'));
show('restock_orders by product, status != CANCELLED', d.restock_orders.find({ productId: { $in: [sampleProduct] }, status: { $ne: 'CANCELLED' } }).explain('executionStats'));
show('receptions by restock order', d.receptions.find({ restockOrderId: { $in: ['x'] } }).explain('executionStats'));
show('positions by product', d.positions.find({ productId: sampleProduct }).explain('executionStats'));
show('stock_adjustments by product', d.stock_adjustments.find({ productId: sampleProduct }).sort({ createdAt: -1 }).limit(20).explain('executionStats'));
print(JSON.stringify({ products: d.products.count(), positions: d.positions.count(), restock_orders: d.restock_orders.count(), receptions: d.receptions.count(), stock_adjustments: d.stock_adjustments.count() }));
