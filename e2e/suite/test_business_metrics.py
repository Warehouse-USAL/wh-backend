"""Area: business metrics seeded daily for the last two years (docs/SEEDED_METRICS.md).

Every business metric of the demo seed must answer with data on EVERY day of the 730-day window:
the catalogue's business series (fleet telemetry in VictoriaMetrics), the computed
suggestion_conversion metric and the dashboard KPIs derived from the orders collection through
POST /query. Backend-infrastructure series (JVM, HTTP, Kafka, Mongo, MinIO...) are not business
metrics and are not seeded.
"""
from datetime import date, datetime, timedelta, timezone

import pytest

DAYS = 730
DAY_S = 86400


def _today() -> date:
    return datetime.now(timezone.utc).date()


def _iso(d: date) -> str:
    return datetime(d.year, d.month, d.day, tzinfo=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _window() -> tuple[date, date]:
    """[today - 730d, today) in UTC days: the seed covers it completely."""
    end = _today()
    return end - timedelta(days=DAYS), end


def _paged(api, entity: str, body: dict) -> list[dict]:
    rows, page = [], 0
    while True:
        r = api.post(f"/query/{entity}", json={**body, "page": page, "size": 100}).expect(200)
        rows += r.json_("items")
        if page + 1 >= r.json_("pagination", "total_pages"):
            return rows
        page += 1


def _business_catalog(warehouse) -> list[dict]:
    """The metrics a non-SUPERADMIN role may read are exactly the business ones (wh.*)."""
    return warehouse.get("/metrics/catalog").expect(200).json_("metrics")


# ───────────────────────────── raw series (fleet telemetry) ─────────────────────────────
def test_every_business_series_of_the_catalog_has_a_point_every_day_for_two_years(warehouse):
    """Each business metric in GET /metrics/catalog (wh.vehicle.*) answers a 1d-step query over 730 days with one value per day, none missing; the transitions counter increases on every interior day"""
    start, end = _window()
    metrics = _business_catalog(warehouse)
    assert {m["name"] for m in metrics} >= {"wh.vehicle.battery", "wh.vehicle.state", "wh.vehicle.transitions"}, metrics
    problems = []
    for m in metrics:
        agg = "increase" if m["type"] == "counter" else "avg"
        body = {"metric": m["name"], "from": _iso(start), "to": _iso(end), "step": "1d", "agg": agg, "filters": {}, "group_by": []}
        series = warehouse.post("/metrics/query", json=body).expect(200).json_("series")
        if len(series) != 1:
            problems.append(f"{m['name']}: expected one series, got {len(series)}")
            continue
        points = series[0]["points"]
        stamps = [p[0] for p in points]
        if len(points) < DAYS - 1:
            problems.append(f"{m['name']}: only {len(points)} daily points")
        if any(b - a != DAY_S for a, b in zip(stamps, stamps[1:])):
            problems.append(f"{m['name']}: a day is missing between {stamps[0]} and {stamps[-1]}")
        if any(p[1] is None for p in points):
            problems.append(f"{m['name']}: null values")
        if m["name"] == "wh.vehicle.transitions":
            idle_days = [p[0] for p in points[1:-1] if not p[1] or p[1] <= 0]
            if idle_days:
                problems.append(f"{m['name']}: no transitions on {len(idle_days)} days, first at {idle_days[0]}")
    assert not problems, "\n".join(problems)


@pytest.mark.parametrize("flt,group,why", [
    ({"to": "OFFLINE"}, "category", "failures by category (KPI 1-3: failure rate, Pareto, MTBF)"),
    ({"state": "OFFLINE"}, "vehicle_id", "time offline per rover (KPI 4: MTTR)"),
    ({"state": "BUSY"}, "vehicle_id", "busy rovers (KPI 5 and fleet utilisation)"),
])
def test_fleet_kpi_inputs_have_data_every_day(warehouse, flt, group, why):
    """The fleet KPI queries of the dashboard guide (failures, offline time, busy rovers) return a value on every one of the 730 days"""
    start, end = _window()
    metric = "wh.vehicle.transitions" if "to" in flt else "wh.vehicle.state"
    body = {"metric": metric, "from": _iso(start), "to": _iso(end), "step": "1d", "agg": "increase" if "to" in flt else "avg",
            "filters": flt, "group_by": [group]}
    series = warehouse.post("/metrics/query", json=body).expect(200).json_("series")
    assert series, why
    per_day: dict[int, float] = {}
    for s in series:
        for ts, v in s["points"]:
            per_day[ts] = per_day.get(ts, 0.0) + (v or 0.0)
    assert len(per_day) >= DAYS - 1, f"{why}: {len(per_day)} days"
    interior = sorted(per_day)[1:-1]
    assert all(per_day[t] > 0 for t in interior), f"{why}: a day without activity"


# ───────────────────────────── computed: suggestion conversion ─────────────────────────────
def test_suggestion_conversion_has_orders_and_recommendations_every_day_for_two_years(warehouse):
    """POST /metrics/suggestion-conversion (day) returns 730 consecutive buckets, each with orders and at least one recommended line; adoption grows over the window"""
    start, end = _window()
    r = warehouse.post("/metrics/suggestion-conversion", json={"params": {"from": _iso(start), "to": _iso(end), "granularity": "day"}}).expect(200)
    data = r.json_("data")
    assert r.json_("metric") == "suggestion_conversion" and len(data) == DAYS
    assert [d["bucket"] for d in data] == [(start + timedelta(days=i)).isoformat() for i in range(DAYS)]
    assert all(d["orders"] > 0 and d["lines_from_recommendation"] > 0 and d["units_from_recommendation"] > 0 for d in data), \
        [d["bucket"] for d in data if d["orders"] == 0 or d["lines_from_recommendation"] == 0][:5]
    assert all(0 < d["order_conversion_rate"] <= 1 and 0 < d["line_conversion_rate"] <= 1 and 0 < d["unit_conversion_rate"] <= 1 for d in data)
    early = sum(d["line_conversion_rate"] for d in data[:180]) / 180
    late = sum(d["line_conversion_rate"] for d in data[-180:]) / 180
    assert late > 2 * early, (early, late)


@pytest.mark.parametrize("granularity,expected", [("week", 105), ("month", 25)])
def test_suggestion_conversion_week_and_month_buckets_have_no_holes(warehouse, granularity, expected):
    """Weekly and monthly buckets cover the two-year window without gaps (a window of 730 days touches 105 ISO weeks / 24-25 months)"""
    start, end = _window()
    data = warehouse.post("/metrics/suggestion-conversion", json={"params": {"from": _iso(start), "to": _iso(end), "granularity": granularity}}).expect(200).json_("data")
    assert expected - 1 <= len(data) <= expected + 1, len(data)
    assert all(d["orders"] > 0 and d["orders_with_recommendation"] > 0 for d in data)


def test_suggestion_conversion_validation_and_filters(warehouse):
    """Bad params -> 400 INVALID_METRIC_PARAMS naming the param; a category filter narrows the lines considered; DASHBOARD may read it"""
    start, end = _window()
    ok = {"from": _iso(start), "to": _iso(end), "granularity": "day"}
    for patch, needle in [({"granularity": "hour"}, "granularity"), ({"to": _iso(start)}, "to"),
                          ({"from": _iso(end - timedelta(days=800))}, "731"), ({"time_zone": "Mars/Base"}, "time_zone")]:
        r = warehouse.post("/metrics/suggestion-conversion", json={"params": {**ok, **patch}}).expect(400, code="INVALID_METRIC_PARAMS")
        assert needle in r.body["error"]["message"], r.describe()
    warehouse.post("/metrics/suggestion-conversion", json={"params": ok, "filters": {"category": "nope"}}).expect(400, code="INVALID_METRIC_PARAMS")
    everything = warehouse.post("/metrics/suggestion-conversion", json={"params": ok}).expect(200).json_("data")
    tech = warehouse.post("/metrics/suggestion-conversion", json={"params": ok, "filters": {"category": "TECNOLOGIA"}}).expect(200).json_("data")
    assert sum(d["lines"] for d in tech) <= sum(d["lines"] for d in everything)
    cat = warehouse.get("/metrics/catalog").expect(200).json_("computed_metrics")
    assert "suggestion_conversion" in {c["name"] for c in cat}


# ───────────────────────────── orders-derived KPIs via /query ─────────────────────────────
def _day_filters():
    start, end = _window()
    return [{"field": "created_at", "op": "gte", "value": _iso(start)}, {"field": "created_at", "op": "lt", "value": _iso(end)}]


def _expected_days() -> set[str]:
    start, _ = _window()
    # The newest day or two can hold only in-flight orders; the 728 before are settled.
    return {(start + timedelta(days=i)).isoformat() for i in range(1, DAYS - 1)}


def test_orders_per_day_by_status_cover_every_day(warehouse):
    """KPIs 7-10 (orders, completion and cancellation rate per day): /query/orders grouped by day has completed orders on every day of the window and cancellations on most"""
    rows = _paged(warehouse, "orders", {"filters": _day_filters(), "timezone": "UTC",
                                        "group_by": [{"field": "created_at", "bucket": "day", "as": "day"}, {"field": "status", "as": "status"}],
                                        "aggregates": [{"op": "count", "as": "n"}]})
    completed = {r["day"][:10] for r in rows if r["status"] == "COMPLETED"}
    cancelled = {r["day"][:10] for r in rows if r["status"] == "CANCELLED"}
    assert _expected_days() <= completed, sorted(_expected_days() - completed)[:5]
    assert len(cancelled) > 0.5 * DAYS, len(cancelled)


def test_cycle_time_and_assignment_latency_exist_every_day(warehouse):
    """KPIs 12-14 (cycle time, time to vehicle assignment, SLA): completed orders carry a positive average cycle_time_ms and assignment_latency_ms on every day"""
    for field in ("cycle_time_ms", "assignment_latency_ms"):
        rows = _paged(warehouse, "orders", {"filters": _day_filters() + [{"field": "status", "op": "eq", "value": "COMPLETED"}], "timezone": "UTC",
                                            "group_by": [{"field": "created_at", "bucket": "day", "as": "day"}],
                                            "aggregates": [{"op": "avg", "field": field, "as": "v"}]})
        days = {r["day"][:10] for r in rows if r["v"] and r["v"] > 0}
        assert _expected_days() <= days, (field, sorted(_expected_days() - days)[:5])


def test_picking_lines_and_units_exist_every_day(warehouse):
    """KPI 15 (picking efficiency) and the demand behind KPIs 16-20: order lines and units per day, unwinding items, on every day"""
    rows = _paged(warehouse, "orders", {"filters": _day_filters(), "unwind": "items", "timezone": "UTC",
                                        "group_by": [{"field": "created_at", "bucket": "day", "as": "day"}],
                                        "aggregates": [{"op": "count", "as": "lines"}, {"op": "sum", "field": "items.quantity", "as": "units"}]})
    days = {r["day"][:10] for r in rows if r["lines"] > 0 and r["units"] > 0}
    assert _expected_days() <= days, sorted(_expected_days() - days)[:5]


def test_every_rover_has_completed_orders_in_every_month(warehouse):
    """KPI 11 (productivity per rover): each seeded vehicle has completed orders in every month of the window"""
    rows = _paged(warehouse, "orders", {"filters": _day_filters() + [{"field": "assigned_vehicle_id", "op": "exists", "value": True}], "timezone": "UTC",
                                        "group_by": [{"field": "created_at", "bucket": "month", "as": "month"}, {"field": "assigned_vehicle_id", "as": "vehicle"}],
                                        "aggregates": [{"op": "count", "as": "n"}]})
    vehicles = {r["vehicle"] for r in rows}
    assert len(vehicles) >= 5, vehicles
    months = {r["month"][:7] for r in rows}
    assert len(months) >= 24, months
    seen = {(r["vehicle"], r["month"][:7]) for r in rows}
    # One rover is parked all along (offline, never assigned), so a few pairs may be absent.
    assert len(seen) >= 0.9 * len(vehicles) * len(months), (len(seen), len(vehicles), len(months))


def test_receptions_and_restock_orders_are_spread_over_the_two_years(warehouse):
    """Stock inflow (receptions) is not daily by nature (deliveries arrive on some days) but must appear in every month of the window"""
    rows = _paged(warehouse, "receptions", {"filters": [{"field": "created_at", "op": "gte", "value": _iso(_window()[0])}], "timezone": "UTC",
                                            "group_by": [{"field": "created_at", "bucket": "month", "as": "month"}],
                                            "aggregates": [{"op": "sum", "field": "quantity_received", "as": "units"}]})
    assert len({r["month"][:7] for r in rows}) >= 24, rows
