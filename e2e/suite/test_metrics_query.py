"""Area: metrics catalog/queries, computed restock suggestions (+apply), entity query endpoint."""
import time
from datetime import datetime, timedelta, timezone

import pytest

from lib.api import wait_until

PARAMS = {"alpha": 0.3, "recent_days": 7, "long_days": 60, "safety_days": 2, "lead_time_days": 5, "coverage_days": 7}


def _iso(d):
    return d.strftime("%Y-%m-%dT%H:%M:%SZ")


def _range(hours=1):
    now = datetime.now(timezone.utc)
    return {"from": _iso(now - timedelta(hours=hours)), "to": _iso(now + timedelta(minutes=1))}


# ───────────────────────────── metrics ─────────────────────────────
def test_metrics_catalog_describes_metrics_and_computed_metrics(admin, warehouse):
    """GET /metrics/catalog lists metric descriptors (name, type, dimensions) and the computed restock_suggestions with its params; non-superadmins see only the business metrics"""
    cat = admin.get("/metrics/catalog").expect(200).body
    wh_names = {m["name"] for m in warehouse.get("/metrics/catalog").expect(200).json_("metrics")}
    assert wh_names and wh_names < {m["name"] for m in cat["metrics"]} and all(n.startswith("wh.") for n in wh_names), wh_names
    names = {m["name"] for m in cat["metrics"]}
    assert {"wh.vehicle.battery", "wh.vehicle.state", "http.server.duration", "kafka.messages", "jvm.heap.used", "bridge.messages", "ws.sessions"} <= names
    battery = next(m for m in cat["metrics"] if m["name"] == "wh.vehicle.battery")
    assert battery["type"] == "gauge" and battery["unit"] == "%" if "unit" in battery else True
    rs = next(c for c in cat["computed_metrics"] if c["name"] == "restock_suggestions")
    assert rs["path"] == "/metrics/restock-suggestions" and {p["name"] for p in rs["params"]} == set(PARAMS)


@pytest.mark.parametrize("patch,code", [
    ({"metric": "nope.metric"}, "UNKNOWN_METRIC"),
    ({"group_by": ["bogus"]}, "UNKNOWN_DIMENSION"),
    ({"filters": {"bogus": "x"}}, "UNKNOWN_DIMENSION"),
    ({"agg": "p99"}, "UNSUPPORTED_AGGREGATION"),
    ({"step": "1s"}, "QUERY_TOO_BROAD"),
    ({"from": "2020-01-01T00:00:00Z"}, "QUERY_TOO_BROAD"),
])
def test_metrics_query_rejections(warehouse, patch, code):
    """Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code"""
    body = {"metric": "wh.vehicle.battery", **_range(), "step": "5m", "agg": "avg", "filters": {}, "group_by": []}
    body.update(patch)
    warehouse.post("/metrics/query", json=body).expect(400, code=code)


def test_metrics_query_validation(warehouse):
    """Missing required fields -> 400 VALIDATION_ERROR"""
    warehouse.post("/metrics/query", json={"metric": "wh.vehicle.battery"}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/metrics/query", content="{x", headers={"Content-Type": "application/json"}).expect(400)


def test_metrics_query_returns_chart_ready_series_for_a_live_vehicle(warehouse, infra, vehicle_id):
    """A vehicle reported through /infra shows up in wh.vehicle.battery as [epochSeconds, value] points (backend -> OTEL -> VictoriaMetrics -> API)"""
    infra_vid, vid = vehicle_id
    infra.telemetry(infra_vid, "idle", 61).expect(200)

    def series():
        r = warehouse.post("/metrics/query", json={"metric": "wh.vehicle.battery", **_range(), "step": "10s", "agg": "last" if False else "avg",
                                                    "filters": {"vehicle_id": vid}, "group_by": ["vehicle_id"]}).expect(200)
        return r.json_("series") or None

    got = wait_until(series, timeout=90, interval=5, desc=f"battery series for {vid} in VictoriaMetrics")
    assert got[0]["labels"] == {"vehicle_id": vid}
    ts, val = got[0]["points"][-1]
    assert abs(ts - time.time()) < 3600 and val == 61.0


# ───────────────────────────── restock suggestions ─────────────────────────────
def test_restock_suggestions_row_shape_and_stock_link(warehouse, factory):
    """A row per active product with demand/safety/reorder/target/inventory fields, available_stock matching the product's stock; filters narrow it"""
    s = factory.stocked(40, minimum_stock=5)
    r = warehouse.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {"product_ids": [s.id]}}).expect(200).body
    assert r["metric"] == "restock_suggestions" and r["params_used"]["alpha"] == 0.3 and r["generated_at"]
    assert len(r["data"]) == 1
    row = r["data"][0]
    assert {"product_id", "sku", "name", "long_term_demand", "recent_demand", "blended_demand", "safety_stock", "reorder_point",
            "target_stock", "available_stock", "on_order_stock", "inventory_position", "should_restock", "suggested_quantity"} <= set(row)
    assert row["product_id"] == s.id and row["available_stock"] == 40 and row["on_order_stock"] == 0 and row["inventory_position"] == 40
    assert row["should_restock"] is False and row["suggested_quantity"] == 0, "no demand history -> nothing to restock"
    cat = warehouse.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {"category": "otros", "product_ids": [s.id]}}).json_("data")
    assert [x["product_id"] for x in cat] == [s.id]
    none = warehouse.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {"category": "ALIMENTOS", "product_ids": [s.id]}}).json_("data")
    assert none == []


def test_restock_suggestions_count_open_restock_orders(warehouse, factory):
    """on_order_stock = quantity of open restock orders minus what has been LOCATED on shelves"""
    s = factory.stocked(10)

    def row():
        return warehouse.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {"product_ids": [s.id]}}).json_("data")[0]

    ro = warehouse.post("/restock/orders", json={"product_id": s.id, "quantity_requested": 30, "supplier": "ACME"}).expect(201).json_("restock_order")
    assert (row()["on_order_stock"], row()["inventory_position"]) == (30, 40)
    # RFC: what counts is what is LOCATED on shelves, not merely received: a PENDING_LOCATION reception is still on order
    rec = warehouse.post("/restock/receptions", json={"restock_order_id": ro["id"], "product_id": s.id, "quantity_received": 12, "delivery_unit": "PALLET", "supplier": "ACME"}).expect(201).json_("reception")
    assert row()["on_order_stock"] == 30
    warehouse.patch(f"/restock/receptions/{rec['id']}", json={"assignments": [{"position_id": s.position["id_position"], "quantity": 12}]}).expect(200)
    assert (row()["on_order_stock"], row()["available_stock"], row()["inventory_position"]) == (18, 22, 40), "located units leave 'on order' and enter the stock"
    warehouse.post("/restock/orders", json={"product_id": s.id, "quantity_requested": 50, "supplier": "ACME"}).expect(201)
    assert row()["on_order_stock"] == 68


def test_restock_suggestions_ignore_cancelled_restock_orders(warehouse, factory):
    """A cancelled restock order is not stock on order"""
    s = factory.stocked(10)
    ro = warehouse.post("/restock/orders", json={"product_id": s.id, "quantity_requested": 50, "supplier": "ACME"}).expect(201).json_("restock_order")
    warehouse.post(f"/restock/orders/{ro['id']}/cancel").expect(200)
    row = warehouse.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {"product_ids": [s.id]}}).json_("data")[0]
    assert (row["on_order_stock"], row["inventory_position"]) == (0, 10)


@pytest.mark.parametrize("patch,needle", [
    ({"alpha": 1.5}, "alpha"), ({"alpha": -0.1}, "alpha"), ({"recent_days": 0}, "recent_days"),
    ({"recent_days": 60, "long_days": 7}, "long_days"), ({"long_days": 400}, "long_days"),
    ({"safety_days": -1}, "safety_days"), ({"lead_time_days": None}, "lead_time_days"),
])
def test_restock_suggestions_param_validation(warehouse, patch, needle):
    """Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender"""
    r = warehouse.post("/metrics/restock-suggestions", json={"params": {**PARAMS, **patch}, "filters": {}}).expect(400, code="INVALID_METRIC_PARAMS")
    assert needle in r.json_("error", "message")


def test_restock_apply_stores_the_recommendation_on_products(warehouse, dashboard, factory):
    """apply writes product.restock for every active product (calculated_at set); filters are refused; DASHBOARD may simulate but not apply"""
    s = factory.stocked(15)
    assert warehouse.get(f"/products/{s.id}").json_("product", "restock") is None
    dashboard.post("/metrics/restock-suggestions", json={"params": PARAMS, "filters": {}}).expect(200)
    dashboard.post("/metrics/restock-suggestions/apply", json={"params": PARAMS}).expect(403)
    warehouse.post("/metrics/restock-suggestions/apply", json={"params": PARAMS, "filters": {"category": "OTROS"}}).expect(400, code="INVALID_METRIC_PARAMS")
    applied = warehouse.post("/metrics/restock-suggestions/apply", json={"params": PARAMS}).expect(200)
    assert any(r["product_id"] == s.id for r in applied.json_("data"))
    rs = warehouse.get(f"/products/{s.id}").json_("product", "restock")
    assert rs["calculated_at"] and rs["should_restock"] is False and rs["inventory_position"] == 15
    assert {"should_restock", "suggested_quantity", "reorder_point", "target_stock", "inventory_position", "calculated_at"} <= set(rs)


# ───────────────────────────── entity query ─────────────────────────────
def test_query_catalog_is_role_scoped(warehouse, sales, dashboard, system, operator):
    """GET /query/catalog lists entities with fields/operators; users only for system admins"""
    names = lambda api: {e["name"] for e in api.get("/query/catalog").expect(200).json_("entities")}  # noqa: E731
    assert {"orders", "products", "vehicles"} <= names(warehouse)
    assert "users" in names(system) and "users" not in names(warehouse) and "users" not in names(sales)
    assert "users" not in names(dashboard)
    f = next(e for e in warehouse.get("/query/catalog").json_("entities") if e["name"] == "orders")["fields"]
    status = next(x for x in f if x["name"] == "status")
    assert status["filterable"] and status["sortable"] and "in" in status["operators"]


def test_query_filters_sort_fields_and_pagination(warehouse, operator, factory):
    """Entity query: filter operators, projection, sort, size ceiling (100), page metadata"""
    s = factory.stocked(20)
    a, b = factory.order(operator, [(s.id, 1)]), factory.order(operator, [(s.id, 2)])
    q = lambda **b: warehouse.post("/query/orders", json=b)  # noqa: E731
    r = q(filters=[{"field": "id", "op": "in", "value": [a["id"], b["id"]]}], fields=["id", "status"], sort=[{"field": "created_at", "dir": "asc"}]).expect(200)
    assert [i["id"] for i in r.json_("items")] == [a["id"], b["id"]] and set(r.json_("items")[0]) == {"id", "status"}
    assert r.json_("items")[0]["status"] == "PENDING"
    assert q(filters=[{"field": "id", "op": "eq", "value": a["id"]}, {"field": "status", "op": "ne", "value": "PENDING"}]).json_("items") == []
    assert q(filters=[{"field": "id", "op": "nin", "value": [a["id"]]}, {"field": "id", "op": "eq", "value": b["id"]}]).json_("pagination", "total_elements") == 1
    assert q(filters=[{"field": "requested_by_user_id", "op": "contains", "value": "operator"}], size=1).json_("pagination", "size") == 1
    assert q(filters=[{"field": "cancel_reason", "op": "exists", "value": False}, {"field": "id", "op": "eq", "value": a["id"]}]).json_("pagination", "total_elements") == 1
    assert q(size=1000).json_("pagination", "size") == 100
    p2 = q(size=2, page=1, sort=[{"field": "created_at", "dir": "desc"}]).expect(200)
    assert p2.json_("pagination", "page") == 1 and len(p2.json_("items")) == 2
    camel = q(filters=[{"field": "createdAt", "op": "gte", "value": "2020-01-01T00:00:00Z"}], size=1).expect(200)
    assert camel.json_("pagination", "total_elements") > 0, "camelCase field names are accepted"
    assert q(filters=[{"field": "id", "op": "contains", "value": ".*"}]).json_("items") == [], "contains is a literal, not a regex"


@pytest.mark.parametrize("body,code", [
    ({"filters": [{"field": "bogus", "op": "eq", "value": 1}]}, "UNKNOWN_FIELD"),
    ({"filters": [{"field": "status", "op": "gt", "value": "A"}]}, "UNSUPPORTED_OPERATOR"),
    ({"filters": [{"field": "created_at", "op": "gte", "value": "not-a-date"}]}, "INVALID_FILTER_VALUE"),
    ({"filters": [{"field": "id", "op": "eq", "value": "x"}] * 11}, "TOO_MANY_FILTERS"),
    ({"fields": ["nope"]}, "UNKNOWN_FIELD"),
    ({"sort": [{"field": "nope", "dir": "asc"}]}, "UNKNOWN_FIELD"),
])
def test_query_rejections(warehouse, body, code):
    """Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code"""
    warehouse.post("/query/orders", json=body).expect(400, code=code)


def test_query_unknown_entity_and_role_limits(warehouse, system, dashboard, operator):
    """Unknown entity 400; users entity: system admins only; DASHBOARD reads orders but entities it may not see are hidden"""
    warehouse.post("/query/nope", json={}).expect(400, code="UNKNOWN_ENTITY")
    warehouse.post("/query/users", json={}).expect(400, code="UNKNOWN_ENTITY")
    assert system.post("/query/users", json={"size": 1}).expect(200).json_("items")
    dashboard.post("/query/orders", json={"size": 1}).expect(200)
    operator.post("/query/orders", json={"size": 1}).expect(200)


def test_query_aggregation_needs_a_date_bound_and_groups(warehouse):
    """Aggregations without a date filter are refused (UNBOUNDED_RANGE); with one they return a row per group"""
    agg = {"group_by": [{"field": "status"}], "aggregates": [{"op": "count", "as": "n"}]}
    warehouse.post("/query/orders", json=agg).expect(400, code="UNBOUNDED_RANGE")
    since = _iso(datetime.now(timezone.utc) - timedelta(days=30))
    bounded = {**agg, "filters": [{"field": "created_at", "op": "gte", "value": since}]}
    rows = warehouse.post("/query/orders", json=bounded).expect(200).json_("items")
    assert {"status", "n"} <= set(rows[0]) and sum(r["n"] for r in rows) >= 1
    top = warehouse.post("/query/orders", json={"filters": bounded["filters"], "unwind": "items", "group_by": [{"field": "items.sku"}],
                                                  "aggregates": [{"op": "sum", "field": "items.quantity", "as": "units"}],
                                                  "sort": [{"field": "units", "dir": "desc"}], "size": 3}).expect(200).json_("items")
    assert 1 <= len(top) <= 3 and [t["units"] for t in top] == sorted((t["units"] for t in top), reverse=True)
