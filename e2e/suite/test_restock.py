"""Area: restock orders and receptions (RFC_Restock_Recepcion, #143 supplier flow, #147 cancellation)."""
from datetime import datetime, timedelta, timezone

import pytest

from lib.api import login
from lib.factory import uid


def _future(days=5):
    return (datetime.now(timezone.utc) + timedelta(days=days)).strftime("%Y-%m-%dT%H:%M:%SZ")


def _restock(api, product_id, qty=10, supplier="ACME", **extra):
    return api.post("/restock/orders", json={"product_id": product_id, "quantity_requested": qty, "supplier": supplier, **extra})


@pytest.fixture
def providers(admin, factory):
    """Two PROVIDER accounts of different suppliers, plus one not linked to any supplier."""
    sup_a, sup_b = f"SupA-{uid(6)}", f"SupB-{uid(6)}"
    a = factory.user(admin, "PROVIDER", supplier=sup_a)
    b = factory.user(admin, "PROVIDER", supplier=sup_b)
    c = factory.user(admin, "PROVIDER")
    return {"a": (login(a.email, a.password), sup_a), "b": (login(b.email, b.password), sup_b), "none": login(c.email, c.password)}


# ───────────────────────────── restock orders ─────────────────────────────
def test_restock_order_create_get_and_list(warehouse, factory):
    """Create a restock order (pending, expected delivery date), read it with lead time and received-so-far, list with filters"""
    p = factory.product()
    exp = _future(7)
    ro = _restock(warehouse, p["id"], 25, "Proveedor X", expected_delivery_date=exp).expect(201).json_("restock_order")
    assert ro["status"] == "PENDING" and ro["quantity_requested"] == 25 and ro["supplier"] == "Proveedor X"
    assert ro["requested_by_user_id"] == warehouse.id and ro["expected_delivery_date"].startswith(exp[:19])
    det = warehouse.get(f"/restock/orders/{ro['id']}").expect(200).json_("restock_order")
    assert det["quantity_received_so_far"] == 0 and det["delivery_lead_time_days"] in (6, 7)
    lst = warehouse.get(f"/restock/orders?productId={p['id']}").expect(200)
    assert [x["id"] for x in lst.json_("restock_orders")] == [ro["id"]] and lst.json_("pagination", "total_elements") == 1
    assert warehouse.get(f"/restock/orders?productId={p['id']}&supplier=nobody").json_("restock_orders") == []
    warehouse.get("/restock/orders?from=garbage").expect(400, code="INVALID_DATE_FORMAT")
    warehouse.get("/restock/orders/nope").expect(404, code="RESTOCK_ORDER_NOT_FOUND")


def test_restock_order_validation(warehouse, factory):
    """Zero quantity / blank supplier / unknown product / expected date in the past are refused"""
    p = factory.product()
    _restock(warehouse, p["id"], 0).expect(400, code="VALIDATION_ERROR")
    _restock(warehouse, p["id"], 5, supplier="").expect(400, code="VALIDATION_ERROR")
    _restock(warehouse, "000000000000000000000000").expect(404, code="PRODUCT_NOT_FOUND")
    _restock(warehouse, p["id"], expected_delivery_date=_future(-1)).expect(400, code="INVALID_EXPECTED_DELIVERY_DATE")
    _restock(warehouse, p["id"], expected_delivery_date=_future(3)).expect(201)  # the date is optional / accepted when future
    _restock(warehouse, p["id"]).expect(201)


def test_restock_order_cancel_rules(warehouse, factory):
    """Cancel: 200 soft delete with reason/author; hidden by default, visible with includeCancelled; cancel twice 409; with receptions 409"""
    p = factory.product()
    ro = _restock(warehouse, p["id"]).expect(201).json_("restock_order")
    c = warehouse.post(f"/restock/orders/{ro['id']}/cancel", json={"reason": "supplier closed"}).expect(200).json_("restock_order")
    assert c["status"] == "CANCELLED" and c["cancellation_reason"] == "supplier closed" and c["cancelled_by_user_id"] == warehouse.id and c["cancelled_at"]
    assert not warehouse.get(f"/restock/orders?productId={p['id']}").json_("restock_orders")
    shown = warehouse.get(f"/restock/orders?productId={p['id']}&includeCancelled=true").json_("restock_orders")
    assert [x["id"] for x in shown] == [ro["id"]] and shown[0]["status"] == "CANCELLED"
    assert warehouse.get(f"/restock/orders/{ro['id']}").json_("restock_order", "status") == "CANCELLED"
    warehouse.post(f"/restock/orders/{ro['id']}/cancel").expect(409, code="RESTOCK_ORDER_ALREADY_CANCELLED")
    warehouse.post("/restock/orders/nope/cancel").expect(404, code="RESTOCK_ORDER_NOT_FOUND")
    # with a reception it can no longer be cancelled
    ro2 = _restock(warehouse, p["id"], 10).expect(201).json_("restock_order")
    warehouse.post("/restock/receptions", json={"restock_order_id": ro2["id"], "product_id": p["id"], "quantity_received": 4,
                                                 "delivery_unit": "CAJA", "supplier": "ACME"}).expect(201)
    warehouse.post(f"/restock/orders/{ro2['id']}/cancel").expect(409, code="RESTOCK_ORDER_HAS_RECEPTIONS")
    assert warehouse.get(f"/restock/orders/{ro2['id']}").json_("restock_order", "quantity_received_so_far") == 4


def test_cancelled_restock_order_refuses_receptions(warehouse, factory):
    """A reception against a cancelled restock order -> 409 RESTOCK_ORDER_CANCELLED"""
    p = factory.product()
    ro = _restock(warehouse, p["id"]).expect(201).json_("restock_order")
    warehouse.post(f"/restock/orders/{ro['id']}/cancel").expect(200)
    warehouse.post("/restock/receptions", json={"restock_order_id": ro["id"], "product_id": p["id"], "quantity_received": 1,
                                                 "delivery_unit": "CAJA", "supplier": "ACME"}).expect(409, code="RESTOCK_ORDER_CANCELLED")


# ───────────────────────────── receptions ─────────────────────────────
def test_reception_with_full_assignment_adds_stock(warehouse, factory):
    """A reception whose assignments add up to the received quantity is COMPLETED and the stock lands in the positions"""
    p = factory.product()
    s = factory.stocked(0, product=p)
    p1 = s.position["id_position"]
    p2 = factory.position(s.line["id_line"], product_id=p["id"], stock=0)["id_position"]
    r = warehouse.post("/restock/receptions", json={"product_id": p["id"], "quantity_received": 30, "delivery_unit": "PALLET", "supplier": "ACME",
                                                     "assignments": [{"position_id": p1, "quantity": 20}, {"position_id": p2, "quantity": 10}]}).expect(201).json_("reception")
    assert r["status"] == "COMPLETED" and r["received_by_user_id"] == warehouse.id and r["restock_order_id"] is None
    assert {(a["position_id"], a["quantity"]) for a in r["assignments"]} == {(p1, 20), (p2, 10)}
    assert factory.stock(p["id"])["physical"] == 30
    assert warehouse.get(f"/restock/receptions/{r['id']}").json_("reception", "assignments") == r["assignments"]


def test_reception_pending_location_then_assign_positions(warehouse, factory):
    """Without assignments the reception waits (PENDING_LOCATION); PATCH assigns positions step by step until COMPLETED, over-assigning is refused"""
    p = factory.product()
    s = factory.stocked(0, product=p)
    pa = s.position["id_position"]
    pb = factory.position(s.line["id_line"], product_id=p["id"], stock=0)["id_position"]
    r = warehouse.post("/restock/receptions", json={"product_id": p["id"], "quantity_received": 12, "delivery_unit": "CAJA", "supplier": "ACME"}).expect(201).json_("reception")
    assert r["status"] == "PENDING_LOCATION" and r["assignments"] == [] and factory.stock(p["id"])["physical"] == 0
    step1 = warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": pa, "quantity": 5}]}).expect(200).json_("reception")
    assert step1["status"] == "PENDING_LOCATION" and factory.stock(p["id"])["physical"] == 5
    warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": pb, "quantity": 8}]}).expect(400, code="ASSIGNMENT_QUANTITY_MISMATCH")
    done = warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": pb, "quantity": 7}]}).expect(200).json_("reception")
    assert done["status"] == "COMPLETED" and len(done["assignments"]) == 2 and factory.stock(p["id"])["physical"] == 12
    warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": pa, "quantity": 1}]}).expect(400, code="RECEPTION_ALREADY_COMPLETED")
    warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": []}).expect(400, code="VALIDATION_ERROR")
    warehouse.patch("/restock/receptions/nope", json={"assignments": [{"position_id": pa, "quantity": 1}]}).expect(404, code="RECEPTION_NOT_FOUND")


def test_reception_assignment_errors(warehouse, factory):
    """Assignments above the received quantity, to an unknown/inactive/occupied position or over capacity are refused and change nothing"""
    p, other = factory.product(), factory.product()
    z = factory.zone()
    ln = factory.line(z["id_zone"])
    ok = factory.position(ln["id_line"], cap=10)
    busy = factory.position(ln["id_line"], product_id=other["id"], stock=1)
    off = factory.position(ln["id_line"], active=False)
    base = {"product_id": p["id"], "quantity_received": 5, "delivery_unit": "PALLET", "supplier": "ACME"}
    warehouse.post("/restock/receptions", json={**base, "assignments": [{"position_id": ok["id_position"], "quantity": 6}]}).expect(400, code="ASSIGNMENT_QUANTITY_MISMATCH")
    warehouse.post("/restock/receptions", json={**base, "assignments": [{"position_id": "nope", "quantity": 1}]}).expect(404, code="POSITION_NOT_FOUND")
    warehouse.post("/restock/receptions", json={**base, "assignments": [{"position_id": busy["id_position"], "quantity": 1}]}).expect(409, code="POSITION_ALREADY_OCCUPIED")
    warehouse.post("/restock/receptions", json={**base, "assignments": [{"position_id": off["id_position"], "quantity": 1}]}).expect(400, code="POSITION_INACTIVE")
    warehouse.post("/restock/receptions", json={**base, "quantity_received": 20, "assignments": [{"position_id": ok["id_position"], "quantity": 11}]}).expect(400, code="STOCK_EXCEEDS_CAPACITY")
    assert factory.stock(p["id"])["physical"] == 0, "failed receptions leave no stock behind"
    warehouse.post("/restock/receptions", json={**base, "assignments": [{"position_id": ok["id_position"], "quantity": 0}]}).expect(400, code="VALIDATION_ERROR")


def test_reception_validation(warehouse, factory):
    """Missing supplier / unknown product / restock-order of another product / bad unit / zero quantity"""
    p, q = factory.product(), factory.product()
    base = {"product_id": p["id"], "quantity_received": 1, "delivery_unit": "CAJA", "supplier": "ACME"}
    warehouse.post("/restock/receptions", json={**base, "supplier": None}).expect(400, code="SUPPLIER_REQUIRED")
    warehouse.post("/restock/receptions", json={**base, "product_id": "000000000000000000000000"}).expect(404, code="PRODUCT_NOT_FOUND")
    warehouse.post("/restock/receptions", json={**base, "quantity_received": 0}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/restock/receptions", json={**base, "delivery_unit": "TRUCK"}).expect(400)
    warehouse.post("/restock/receptions", json={**base, "restock_order_id": "nope"}).expect(404, code="RESTOCK_ORDER_NOT_FOUND")
    ro = _restock(warehouse, q["id"]).expect(201).json_("restock_order")
    warehouse.post("/restock/receptions", json={**base, "restock_order_id": ro["id"]}).expect(400, code="RESTOCK_ORDER_PRODUCT_MISMATCH")
    warehouse.get("/restock/receptions/nope").expect(404, code="RECEPTION_NOT_FOUND")


@pytest.mark.known_failure(bug="BUG-RECEPTION-1", reason="an unknown delivery_unit in a JSON body answers MALFORMED_REQUEST ('not valid JSON') instead of INVALID_DELIVERY_UNIT: StockSize.fromValue throws IllegalArgumentException from the @JsonCreator, which Jackson wraps in ValueInstantiationException, while GlobalExceptionHandler.handleMalformedRequest only maps InvalidFormatException (the query-param path does return INVALID_DELIVERY_UNIT)")
def test_reception_unknown_delivery_unit_error_code(warehouse, factory):
    """POST /restock/receptions with an unknown delivery_unit explains it (INVALID_DELIVERY_UNIT)"""
    p = factory.product()
    warehouse.post("/restock/receptions", json={"product_id": p["id"], "quantity_received": 1, "delivery_unit": "TRUCK", "supplier": "ACME"}).expect(400, code="INVALID_DELIVERY_UNIT")


def test_reception_listing_filters(warehouse, factory):
    """List receptions by product, restock order, status; newest first"""
    p = factory.product()
    ro = _restock(warehouse, p["id"], 10).expect(201).json_("restock_order")
    base = {"product_id": p["id"], "delivery_unit": "CAJA", "supplier": "ACME"}
    r1 = warehouse.post("/restock/receptions", json={**base, "restock_order_id": ro["id"], "quantity_received": 2}).expect(201).json_("reception")
    r2 = warehouse.post("/restock/receptions", json={**base, "quantity_received": 3}).expect(201).json_("reception")
    allp = warehouse.get(f"/restock/receptions?productId={p['id']}").expect(200)
    assert [x["id"] for x in allp.json_("receptions")] == [r2["id"], r1["id"]]
    assert [x["id"] for x in warehouse.get(f"/restock/receptions?restockOrderId={ro['id']}").json_("receptions")] == [r1["id"]]
    assert len(warehouse.get(f"/restock/receptions?productId={p['id']}&status=PENDING_LOCATION").json_("receptions")) == 2
    assert warehouse.get(f"/restock/receptions?productId={p['id']}&status=COMPLETED").json_("receptions") == []
    warehouse.get("/restock/receptions?from=garbage").expect(400, code="INVALID_DATE_FORMAT")
    warehouse.get("/restock/receptions?status=WEIRD").expect(400)


# ───────────────────────────── PROVIDER scoping ─────────────────────────────
def test_provider_sees_only_its_own_suppliers_restock_orders(warehouse, factory, providers):
    """A PROVIDER's listing is forced to its supplier; another supplier's order is 404 (not 403); an unlinked provider is 403 PROVIDER_WITHOUT_SUPPLIER"""
    (pa, sup_a), (pb, sup_b), none = providers["a"], providers["b"], providers["none"]
    p = factory.product()
    mine = _restock(warehouse, p["id"], 5, sup_a).expect(201).json_("restock_order")
    theirs = _restock(warehouse, p["id"], 5, sup_b).expect(201).json_("restock_order")
    ids = {x["id"] for x in pa.get(f"/restock/orders?productId={p['id']}").expect(200).json_("restock_orders")}
    assert ids == {mine["id"]}
    forced = pa.get(f"/restock/orders?productId={p['id']}&supplier={sup_b}").json_("restock_orders")
    assert {x["id"] for x in forced} == {mine["id"]}, "supplier filter cannot be overridden by a provider"
    pa.get(f"/restock/orders/{mine['id']}").expect(200)
    pa.get(f"/restock/orders/{theirs['id']}").expect(404, code="RESTOCK_ORDER_NOT_FOUND")
    none.get("/restock/orders").expect(403, code="PROVIDER_WITHOUT_SUPPLIER")
    none.get(f"/restock/orders/{mine['id']}").expect(403, code="PROVIDER_WITHOUT_SUPPLIER")
    pa.post("/restock/orders", json={"product_id": p["id"], "quantity_requested": 1, "supplier": sup_a}).expect(403)
    pa.post(f"/restock/orders/{mine['id']}/cancel").expect(403)


def test_provider_delivers_against_its_order(warehouse, factory, providers):
    """A PROVIDER registers a reception against its own restock order only (no assignments, supplier forced); positions are assigned later by the warehouse"""
    (pa, sup_a), (pb, sup_b), none = providers["a"], providers["b"], providers["none"]
    s = factory.stocked(0)
    p = s.product
    mine = _restock(warehouse, p["id"], 10, sup_a).expect(201).json_("restock_order")
    theirs = _restock(warehouse, p["id"], 10, sup_b).expect(201).json_("restock_order")
    base = {"product_id": p["id"], "quantity_received": 6, "delivery_unit": "CAJA"}
    pa.post("/restock/receptions", json={**base}).expect(400, code="RESTOCK_ORDER_REQUIRED")
    pa.post("/restock/receptions", json={**base, "restock_order_id": mine["id"], "assignments": [{"position_id": s.position["id_position"], "quantity": 6}]}).expect(400, code="ASSIGNMENTS_NOT_ALLOWED")
    pa.post("/restock/receptions", json={**base, "restock_order_id": theirs["id"]}).expect(404, code="RESTOCK_ORDER_NOT_FOUND")
    none.post("/restock/receptions", json={**base, "restock_order_id": mine["id"]}).expect(403, code="PROVIDER_WITHOUT_SUPPLIER")
    r = pa.post("/restock/receptions", json={**base, "restock_order_id": mine["id"], "supplier": "SPOOFED"}).expect(201).json_("reception")
    assert r["supplier"] == sup_a, "a provider cannot deliver on behalf of another supplier name"
    assert r["status"] == "PENDING_LOCATION" and factory.stock(p["id"])["physical"] == 0
    assert warehouse.get(f"/restock/orders/{mine['id']}").json_("restock_order", "quantity_received_so_far") == 6
    pa.get("/restock/receptions").expect(403)  # providers can't browse receptions
    pa.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": s.position["id_position"], "quantity": 6}]}).expect(403)
    done = warehouse.patch(f"/restock/receptions/{r['id']}", json={"assignments": [{"position_id": s.position["id_position"], "quantity": 6}]}).expect(200).json_("reception")
    assert done["status"] == "COMPLETED" and factory.stock(p["id"])["physical"] == 6
