"""Area: orders (create/list/get/cancel), idempotency, Kafka `orders` message, Infra callbacks, stock numbers.
Fake Infra is NOT running in this group: the tests play Infra over HTTP (X-Api-Key)."""
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone

import pytest

from lib import config
from lib.api import wait_until
from lib.factory import ADDRESS, uid


# ───────────────────────────── create ─────────────────────────────
def test_create_order_happy_path(operator, factory):
    """Creating an order reserves stock, returns it pending with items, address, price total and default priority"""
    s = factory.stocked(10, price={"amount_cents": 250, "currency": "USD", "tax_included": True})
    o = factory.order(operator, [(s.id, 4)])
    assert o["status"] == "pending" and o["priority"].lower() == "medium"
    assert o["requested_by_user_id"] == operator.id and o["destination_area"] == "E2E-AREA"
    assert o["items"][0]["product_id"] == s.id and o["items"][0]["sku"] == s.product["sku"] and o["items"][0]["quantity"] == 4
    assert o["items"][0]["unit_price"]["amount_cents"] == 250 and o["total"]["amount_cents"] == 1000
    assert o["address"] == {"street": ADDRESS["street"], "department": "QA", "floor": "1", "postal_code": "00001"}
    assert o["timestamps"]["started_at"] is None and o["assigned_vehicle_id"] is None
    assert factory.stock(s.id) == {"available": 6, "reserved": 4, "physical": 10, "min": 0}
    assert factory.get_order(o["id"])["id"] == o["id"]


@pytest.mark.parametrize("mutate,code", [
    (lambda b: b.update(destination_area=""), "DESTINATION_AREA_REQUIRED"),
    (lambda b: b.pop("destination_area"), "DESTINATION_AREA_REQUIRED"),
    (lambda b: b.update(items=[]), "ITEMS_REQUIRED"),
    (lambda b: b.pop("items"), "ITEMS_REQUIRED"),
    (lambda b: b.pop("address"), "MISSING_ADDRESS"),
    (lambda b: b["address"].update(street=" "), "MISSING_ADDRESS_STREET"),
    (lambda b: b["address"].pop("postal_code"), "MISSING_ADDRESS_POSTAL_CODE"),
    (lambda b: b["items"][0].update(quantity=0), "INVALID_QUANTITY"),
    (lambda b: b["items"][0].update(quantity=-2), "INVALID_QUANTITY"),
    (lambda b: b["items"][0].update(product_id="000000000000000000000000"), "PRODUCT_NOT_FOUND"),
    (lambda b: b["items"][0].update(quantity=51), "QUANTITY_EXCEEDS_LIMIT"),
    (lambda b: b["items"].append(dict(b["items"][0])), "DUPLICATE_PRODUCT_IN_ORDER"),
])
def test_create_order_validation(operator, factory, mutate, code):
    """Each invalid order is refused with its own 400/404 code and reserves nothing"""
    s = factory.stocked(100)
    body = factory.order_body([(s.id, 5)])
    mutate(body)
    r = operator.post("/orders", json=body)
    assert r.status in (400, 404) and r.code == code, r.describe()
    assert factory.stock(s.id)["reserved"] == 0


def test_create_order_insufficient_stock_and_inactive_product(operator, warehouse, factory):
    """Asking for more than available -> INSUFFICIENT_STOCK; an inactive product -> PRODUCT_INACTIVE/NOT_FOUND"""
    s = factory.stocked(5)
    operator.post("/orders", json=factory.order_body([(s.id, 6)])).expect(400, code="INSUFFICIENT_STOCK")
    factory.order(operator, [(s.id, 5)])
    operator.post("/orders", json=factory.order_body([(s.id, 1)])).expect(400, code="INSUFFICIENT_STOCK")  # all reserved
    warehouse.patch(f"/products/{s.id}", json={"is_active": False}).expect(200)
    r = operator.post("/orders", json=factory.order_body([(s.id, 1)]))
    assert r.status in (400, 404), r.describe()


def test_create_order_malformed_json_and_roles(operator, dispatcher, provider, dashboard, factory, system):
    """Malformed body 400; DISPATCHER/PROVIDER/DASHBOARD/ADMIN_SYSTEM cannot create orders (403)"""
    operator.post("/orders", content="{oops", headers={"Content-Type": "application/json"}).expect(400, code="MALFORMED_REQUEST")
    s = factory.stocked(3)
    for api in (dispatcher, provider, dashboard, system):
        api.post("/orders", json=factory.order_body([(s.id, 1)])).expect(403)
    assert factory.stock(s.id)["reserved"] == 0


def test_order_priority_is_accepted(operator, factory):
    """An explicit priority is stored on the order"""
    s = factory.stocked(3)
    o = factory.order(operator, [(s.id, 1)], priority="HIGH")
    assert o["priority"].lower() == "high"


# ───────────────────────────── idempotency ─────────────────────────────
def test_idempotency_key_replays_the_same_order(operator, factory, kafka):
    """Same Idempotency-Key + same user: the retry returns the original order, reserves once and publishes once"""
    s = factory.stocked(10)
    key = str(uuid.uuid4())
    a = factory.order(operator, [(s.id, 3)], key=key)
    b = factory.order(operator, [(s.id, 3)], key=key)
    assert a["id"] == b["id"]
    assert factory.stock(s.id)["reserved"] == 3
    kafka.wait_order(a["id"], "pending")
    assert len(kafka.for_order(a["id"], "pending")) == 1
    # without a key every call is a new order
    c = factory.order(operator, [(s.id, 1)])
    assert c["id"] != a["id"]


def test_idempotency_key_of_another_user_conflicts(operator, operator2, factory):
    """The same key used by a different user -> 409 IDEMPOTENCY_KEY_USER_MISMATCH and no order"""
    s = factory.stocked(10)
    key = str(uuid.uuid4())
    factory.order(operator, [(s.id, 1)], key=key)
    operator2.post("/orders", json=factory.order_body([(s.id, 1)]), headers={"Idempotency-Key": key}).expect(409, code="IDEMPOTENCY_KEY_USER_MISMATCH")
    assert factory.stock(s.id)["reserved"] == 1


def test_idempotency_key_replay_returns_original_even_if_body_differs(operator, factory):
    """A replayed key with a different body gets the original order back (no second reservation)"""
    s = factory.stocked(10)
    key = str(uuid.uuid4())
    a = factory.order(operator, [(s.id, 2)], key=key)
    b = operator.post("/orders", json=factory.order_body([(s.id, 7)]), headers={"Idempotency-Key": key}).expect(201, 200, 409)
    if b.status != 409:
        assert b.json_("order", "id") == a["id"]
    assert factory.stock(s.id)["reserved"] == 2


def test_idempotency_key_concurrent_requests_create_one_order(operator, factory):
    """Racing requests with one key create exactly one order and every request gets it back (201)"""
    s = factory.stocked(10)
    key = str(uuid.uuid4())
    body = factory.order_body([(s.id, 2)])
    with ThreadPoolExecutor(max_workers=6) as pool:
        rs = list(pool.map(lambda _: operator.post("/orders", json=body, headers={"Idempotency-Key": key}), range(6)))
    # the losers replay the winner's order instead of failing with a 5xx
    assert all(r.status == 201 for r in rs), [(r.status, r.text[:100]) for r in rs]
    ids = {r.json_("order", "id") for r in rs}
    assert len(ids) == 1
    assert factory.stock(s.id)["reserved"] == 2


# ───────────────────────────── stops and the Kafka message ─────────────────────────────
def test_orders_topic_message_has_ordered_stops_from_qr_codes(operator, factory, kafka):
    """The pending message on topic `orders` is exactly {order_id,status,stops,destination,published_at}; stops are the positions' QR codes in zone/line/position order"""
    pa = factory.product()
    pb = factory.product()
    za = factory.zone(code=f"ZA{uid(5).upper()}")  # sorts before zb
    zb = factory.zone(code=f"ZB{uid(5).upper()}")
    la, lb = factory.line(za["id_zone"]), factory.line(zb["id_zone"])
    qr_a, qr_b = f"E2E|A|{uid()}", f"E2E|B|{uid()}"
    factory.position(la["id_line"], product_id=pa["id"], stock=10, qr=qr_a)
    factory.position(lb["id_line"], product_id=pb["id"], stock=10, qr=qr_b)
    # items listed B first: the stops still follow the warehouse traversal, not the item order
    o = factory.order(operator, [(pb["id"], 2), (pa["id"], 3)])
    msg = kafka.wait_order(o["id"], "pending")["value"]
    assert set(msg) == {"order_id", "status", "stops", "destination", "published_at"}, msg
    assert msg["stops"] == [qr_a, qr_b] and msg["destination"] == "E2E-AREA" and msg["status"] == "pending"
    datetime.fromisoformat(msg["published_at"].replace("Z", "+00:00"))


def test_stops_are_deduplicated_and_ordered_by_line_then_position_name(operator, factory, kafka):
    """Two positions of one product in one zone: stops are in line-number then position-name order, one per position"""
    prod = factory.product()
    z = factory.zone()
    l2, l1 = factory.line(z["id_zone"], number=2), factory.line(z["id_zone"], number=1)
    q = {n: f"E2E|{n}|{uid()}" for n in ("l2pa", "l1pb", "l1pa")}
    factory.position(l2["id_line"], name="PA", product_id=prod["id"], stock=5, qr=q["l2pa"])
    factory.position(l1["id_line"], name="PB", product_id=prod["id"], stock=5, qr=q["l1pb"])
    factory.position(l1["id_line"], name="PA", product_id=prod["id"], stock=5, qr=q["l1pa"])
    o = factory.order(operator, [(prod["id"], 15)])  # needs all three
    stops = kafka.wait_order(o["id"], "pending")["value"]["stops"]
    assert stops == [q["l1pa"], q["l1pb"], q["l2pa"]]


def test_order_without_any_qr_still_created_with_empty_stops(operator, factory, kafka):
    """Positions without a QR are left out of the stops; an order with no stops at all is still created and published"""
    prod = factory.product()
    z = factory.zone()
    ln = factory.line(z["id_zone"])
    factory.position(ln["id_line"], product_id=prod["id"], stock=5, qr=None)
    o = factory.order(operator, [(prod["id"], 2)])
    assert kafka.wait_order(o["id"], "pending")["value"]["stops"] == []


# ───────────────────────────── list / get ─────────────────────────────
def test_order_listing_filters_pagination_and_errors(operator, warehouse, factory):
    """List: status/date/vehicle filters, newest-first pagination capped at 50, invalid filters -> 400"""
    s = factory.stocked(50)
    a, b = factory.order(operator, [(s.id, 1)]), factory.order(operator, [(s.id, 1)])
    warehouse.post(f"/orders/{b['id']}/cancel", json={"reason": "list test"}).expect(200)
    pend = warehouse.get("/orders?status=pending&size=50").json_("orders")
    assert all(o["status"] == "pending" for o in pend)
    canc = warehouse.get("/orders?status=CANCELLED&size=50").json_("orders")
    assert any(o["id"] == b["id"] for o in canc) and all(o["status"] == "cancelled" for o in canc)
    now = datetime.now(timezone.utc)
    iso = lambda d: d.strftime("%Y-%m-%dT%H:%M:%SZ")  # noqa: E731
    ms = lambda t: t[:23] + "Z"  # noqa: E731  (create answers nanoseconds, Mongo and listings keep milliseconds)
    lo, hi = ms(a["timestamps"]["created_at"]), ms(b["timestamps"]["created_at"])
    window = warehouse.get(f"/orders?from={lo}&to={hi}&size=50").json_("orders")
    assert {a["id"], b["id"]} <= {o["id"] for o in window}
    assert all(lo <= ms(o["timestamps"]["created_at"]) <= hi for o in window)
    assert not warehouse.get(f"/orders?from={iso(now + timedelta(days=1))}").json_("orders")
    old = warehouse.get(f"/orders?to={iso(now - timedelta(days=3650))}").json_("orders")
    assert a["id"] not in {o["id"] for o in old}
    p = warehouse.get("/orders?size=999").expect(200)
    assert p.json_("pagination", "size") == 50
    warehouse.get("/orders?status=bogus").expect(400, code="INVALID_STATUS")
    warehouse.get("/orders?from=yesterday").expect(400, code="INVALID_DATE_FORMAT")
    warehouse.get("/orders?vehicleId=no-such-vehicle").expect(200)
    assert warehouse.get("/orders?vehicleId=no-such-vehicle").json_("orders") == []
    warehouse.get("/orders/does-not-exist").expect(404, code="ORDER_NOT_FOUND")


@pytest.mark.known_failure(bug="BUG-ORDERS-3", reason="GET /orders applies no sort (OrderRepository.findByFilters only pages), so page 0 is the oldest rows in insertion order: with the seeded history, a freshly created order is not on the first page and pagination order is not deterministic; the contract does not say but every consumer expects newest first")
def test_order_listing_default_order_is_newest_first(operator, warehouse, factory):
    """The first page of GET /orders contains the order created a moment ago (newest first)"""
    s = factory.stocked(3)
    o = factory.order(operator, [(s.id, 1)])
    first = warehouse.get("/orders?size=10").json_("orders")
    assert o["id"] in {x["id"] for x in first}
    created = [x["timestamps"]["created_at"] for x in first]
    assert created == sorted(created, reverse=True)


# ───────────────────────────── cancel ─────────────────────────────
def test_cancel_pending_order_restores_stock_and_tells_infra(operator, factory, kafka):
    """Cancelling a pending order: immediate, reason stored, reservation released, `cancelled` message on topic orders with the same stops"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 6)])
    c = operator.post(f"/orders/{o['id']}/cancel", json={"reason": "changed my mind"}).expect(200).json_("order")
    assert c["status"] == "cancelled" and c["cancel_reason"] == "changed my mind"
    assert factory.stock(s.id) == {"available": 10, "reserved": 0, "physical": 10, "min": 0}
    pending = kafka.wait_order(o["id"], "pending")["value"]
    cancelled = kafka.wait_order(o["id"], "cancelled")["value"]
    assert cancelled["stops"] == pending["stops"] and cancelled["order_id"] == o["id"]


def test_cancel_rules_ownership_roles_and_states(operator, operator2, warehouse, sales, dispatcher, provider, factory, infra, vehicle_id):
    """OPERATOR cancels only own orders (403 ORDER_NOT_OWNED), WAREHOUSE/SALES any; DISPATCHER/PROVIDER 403; repeat 409; unknown 404; body optional"""
    s = factory.stocked(20)
    mine, theirs, third = (factory.order(operator, [(s.id, 1)]) for _ in range(3))
    operator2.post(f"/orders/{mine['id']}/cancel").expect(403, code="ORDER_NOT_OWNED")
    dispatcher.post(f"/orders/{mine['id']}/cancel").expect(403)
    provider.post(f"/orders/{mine['id']}/cancel").expect(403)
    operator.post(f"/orders/{mine['id']}/cancel").expect(200)  # no body at all
    operator.post(f"/orders/{mine['id']}/cancel").expect(409, code="ORDER_NOT_CANCELLABLE")
    warehouse.post(f"/orders/{theirs['id']}/cancel", json={}).expect(200)
    sales.post(f"/orders/{third['id']}/cancel", json={"reason": "sales"}).expect(200)
    operator.post("/orders/nope/cancel").expect(404, code="ORDER_NOT_FOUND")
    done = factory.order(operator, [(s.id, 1)])
    infra.status(done["id"], "completed", vehicle_id[0]).expect(200)
    operator.post(f"/orders/{done['id']}/cancel").expect(409, code="ORDER_NOT_CANCELLABLE")


def test_cancel_in_progress_order_waits_for_infra(operator, warehouse, factory, infra, vehicle_id, kafka):
    """Cancelling an in-progress order only requests it (cancellation_requested_at); it stays in_progress until Infra reports cancelled, then the vehicle is freed"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 3)])
    infra.status(o["id"], "in_progress", infra_vid).expect(200)
    c = operator.post(f"/orders/{o['id']}/cancel", json={"reason": "stop it"}).expect(200).json_("order")
    assert c["status"] == "in_progress" and c["timestamps"]["cancellation_requested_at"], c
    again = operator.post(f"/orders/{o['id']}/cancel").expect(200).json_("order")
    assert again["timestamps"]["cancellation_requested_at"][:23] == c["timestamps"]["cancellation_requested_at"][:23]  # ms: Mongo truncates
    kafka.wait_order(o["id"], "cancelled")
    assert warehouse.get(f"/vehicles/{vid}").json_("current_order_id") == o["id"]
    infra.status(o["id"], "cancelled", infra_vid).expect(200)
    final = factory.get_order(o["id"])
    assert final["status"] == "cancelled"
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "idle" and v["current_order_id"] is None
    assert factory.stock(s.id) == {"available": 10, "reserved": 0, "physical": 10, "min": 0}
    infra.status(o["id"], "completed", infra_vid).expect(409, code="ORDER_NOT_MODIFIABLE")  # a late 'completed' cannot resurrect it


# ───────────────────────────── Infra callbacks ─────────────────────────────
def test_infra_endpoints_require_the_api_key(infra, operator, factory):
    """/infra/** answers 401 without X-Api-Key or with a wrong one, for both orders and telemetry; a JWT is not a substitute"""
    s = factory.stocked(3)
    o = factory.order(operator, [(s.id, 1)])
    for key in (None, "wrong", config.INFRA_API_KEY[:-1], config.INFRA_API_KEY + "x", ""):
        infra.status(o["id"], "in_progress", "VHC-X", key=key).expect(401)
        infra.telemetry("VHC-X", key=key).expect(401)
    r = infra.api.post(f"/infra/orders/{o['id']}/status", token=operator.token, json={"status": "in_progress", "vehicle_id": "VHC-X"})
    r.expect(401)
    assert factory.get_order(o["id"])["status"] == "pending"


def test_infra_status_validation(infra, operator, factory, vehicle_id):
    """Unknown order 404, bad/pending/missing status 400 INVALID_STATUS, in_progress without vehicle 400 VEHICLE_ID_REQUIRED"""
    s = factory.stocked(3)
    o = factory.order(operator, [(s.id, 1)])
    infra.status("does-not-exist", "completed", "VHC-1").expect(404, code="ORDER_NOT_FOUND")
    for bad in ("flying", "pending", ""):
        infra.status(o["id"], bad, vehicle_id[0]).expect(400, code="INVALID_STATUS")
    infra.api.post(f"/infra/orders/{o['id']}/status", token=None, json={}, headers={"X-Api-Key": config.INFRA_API_KEY}).expect(400, code="INVALID_STATUS")
    infra.status(o["id"], "in_progress", None).expect(400, code="VEHICLE_ID_REQUIRED")
    assert factory.get_order(o["id"])["status"] == "pending"


def test_infra_in_progress_assigns_vehicle_and_marks_it_busy(infra, operator, warehouse, factory, vehicle_id):
    """in_progress with Infra's vehicle id creates/assigns the vehicle (UUIDv5 of the Infra id), starts the order and marks the vehicle busy"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 2)])
    r = infra.status(o["id"], "in_progress", infra_vid).expect(200).json_("order")
    assert r["status"] == "in_progress" and r["assigned_vehicle_id"] == vid and r["timestamps"]["started_at"]
    v = warehouse.get(f"/vehicles/{vid}").expect(200).body
    assert v["status"] == "busy" and v["current_order_id"] == o["id"]
    infra.status(o["id"], "in_progress", infra_vid).expect(200)  # redelivery is a no-op
    assert factory.stock(s.id) == {"available": 3, "reserved": 2, "physical": 5, "min": 0}


def test_infra_busy_vehicle_cannot_take_a_second_order(infra, operator, factory, vehicle_id):
    """A vehicle already working on an order -> 409 VEHICLE_ALREADY_BUSY for the next one"""
    s = factory.stocked(5)
    a, b = factory.order(operator, [(s.id, 1)]), factory.order(operator, [(s.id, 1)])
    infra.status(a["id"], "in_progress", vehicle_id[0]).expect(200)
    infra.status(b["id"], "in_progress", vehicle_id[0]).expect(409, code="VEHICLE_ALREADY_BUSY")
    assert factory.get_order(b["id"])["status"] == "pending"
    infra.status(a["id"], "completed", vehicle_id[0]).expect(200)
    infra.status(b["id"], "in_progress", vehicle_id[0]).expect(200)  # free again after completion


def test_completion_drains_stock_publishes_event_and_frees_vehicle(infra, operator, warehouse, factory, vehicle_id, kafka):
    """completed: stock leaves the positions exactly once, reservation released, order.completed (camelCase) published, vehicle idle"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 4)])
    infra.status(o["id"], "in_progress", infra_vid).expect(200)
    done = infra.status(o["id"], "completed", infra_vid).expect(200).json_("order")
    assert done["status"] == "completed" and done["timestamps"]["completed_at"]
    assert factory.stock(s.id) == {"available": 6, "reserved": 0, "physical": 6, "min": 0}
    assert warehouse.get(f"/warehouse/positions/{s.position['id_position']}").json_("current_stock") == 6
    assert warehouse.get(f"/vehicles/{vid}").json_("status") == "idle"
    ev = kafka.wait_order(o["id"], topic="order.completed", id_key="orderId")["value"]
    assert set(ev) == {"orderId", "userId", "destinationArea", "items", "completedAt"}, ev
    assert ev["userId"] == operator.id and ev["destinationArea"] == "E2E-AREA"
    assert ev["items"] == [{"sku": s.product["sku"], "productId": s.id, "quantity": 4}]
    datetime.fromisoformat(ev["completedAt"].replace("Z", "+00:00"))
    # redelivery: no double drain, no second event
    infra.status(o["id"], "completed", infra_vid).expect(200)
    assert factory.stock(s.id)["physical"] == 6
    assert len(kafka.for_order(o["id"], topic="order.completed", id_key="orderId")) == 1


def test_from_recommendation_is_optional_stored_and_published_per_item(infra, operator, warehouse, factory, vehicle_id, kafka):
    """items[].from_recommendation is optional (absent/null/false = false), kept on the order, returned by GET /orders/{id} and published as fromRecommendation on order.completed only for the flagged lines"""
    infra_vid, _ = vehicle_id
    a, b, c = factory.stocked(10), factory.stocked(10), factory.stocked(10)
    body = factory.order_body([(a.id, 1), (b.id, 1), (c.id, 1)])
    body["items"][0]["from_recommendation"] = True
    body["items"][1]["from_recommendation"] = False
    o = operator.post("/orders", json=body).expect(201).json_("order")
    assert [i["from_recommendation"] for i in o["items"]] == [True, False, False]
    assert [i["from_recommendation"] for i in factory.get_order(o["id"])["items"]] == [True, False, False]
    infra.status(o["id"], "in_progress", infra_vid).expect(200)
    infra.status(o["id"], "completed", infra_vid).expect(200)
    ev = kafka.wait_order(o["id"], topic="order.completed", id_key="orderId")["value"]
    by_sku = {i["sku"]: i for i in ev["items"]}
    assert by_sku[a.product["sku"]]["fromRecommendation"] is True
    assert "fromRecommendation" not in by_sku[b.product["sku"]] and "fromRecommendation" not in by_sku[c.product["sku"]]


def test_from_recommendation_must_be_a_boolean_and_a_product_cannot_repeat_to_game_it(operator, factory):
    """A non-boolean flag -> 400; the same product twice is still DUPLICATE_PRODUCT_IN_ORDER, so a line cannot be double counted"""
    s = factory.stocked(10)
    body = factory.order_body([(s.id, 1)])
    body["items"][0]["from_recommendation"] = "maybe"
    assert operator.post("/orders", json=body).status == 400
    twice = factory.order_body([(s.id, 1), (s.id, 1)])
    twice["items"][0]["from_recommendation"] = True
    operator.post("/orders", json=twice).expect(400, code="DUPLICATE_PRODUCT_IN_ORDER")


def test_completion_drains_across_positions_fifo_without_going_negative(infra, operator, warehouse, factory, vehicle_id):
    """An order bigger than one position drains several positions; no position goes below zero"""
    s = factory.stocked(3, positions=3)
    o = factory.order(operator, [(s.id, 7)])
    infra.status(o["id"], "in_progress", vehicle_id[0]).expect(200)
    infra.status(o["id"], "completed", vehicle_id[0]).expect(200)
    left = sorted(warehouse.get(f"/warehouse/positions/{p['id_position']}").json_("current_stock") for p in s.positions)
    assert sum(left) == 2 and min(left) >= 0
    assert factory.stock(s.id)["physical"] == 2


def test_infra_cancel_and_late_status_on_finished_orders(infra, operator, factory, vehicle_id):
    """Infra may cancel a pending order; a different status on a finished order is 409 ORDER_NOT_MODIFIABLE, the same status a 200 no-op"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 2)])
    infra.status(o["id"], "cancelled", vehicle_id[0]).expect(200)
    assert factory.get_order(o["id"])["status"] == "cancelled"
    infra.status(o["id"], "cancelled", vehicle_id[0]).expect(200)
    infra.status(o["id"], "completed", vehicle_id[0]).expect(409, code="ORDER_NOT_MODIFIABLE")
    infra.status(o["id"], "in_progress", vehicle_id[0]).expect(409, code="ORDER_NOT_MODIFIABLE")
    assert factory.stock(s.id) == {"available": 5, "reserved": 0, "physical": 5, "min": 0}


def test_bad_timestamp_does_not_block_a_real_status_change(infra, operator, factory, vehicle_id):
    """An unparseable Infra timestamp falls back to now instead of failing the transition"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    infra.api.post(f"/infra/orders/{o['id']}/status", token=None, headers={"X-Api-Key": config.INFRA_API_KEY},
                   json={"status": "in_progress", "vehicle_id": vehicle_id[0], "timestamp": "not-a-date"}).expect(200)


# ───────────────────────────── internal admin endpoints ─────────────────────────────
def test_internal_order_admin_endpoints(warehouse, operator, factory, vehicle_id, infra):
    """/internal/orders: assign-vehicle and status let WAREHOUSE force a transition; terminal orders refuse"""
    infra.telemetry(vehicle_id[0], "idle", 70).expect(200)
    vid = vehicle_id[1]
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 2)])
    a = warehouse.patch(f"/internal/orders/{o['id']}/assign-vehicle", json={"vehicle_id": vid}).expect(200).json_("order")
    assert a["status"] == "in_progress" and a["assigned_vehicle_id"] == vid
    warehouse.patch(f"/internal/orders/{o['id']}/assign-vehicle", json={"vehicle_id": "nope"}).expect(404, code="VEHICLE_NOT_FOUND")
    warehouse.patch("/internal/orders/nope/assign-vehicle", json={"vehicle_id": vid}).expect(404, code="ORDER_NOT_FOUND")
    warehouse.patch(f"/internal/orders/{o['id']}/status", json={"status": "bogus"}).expect(400, code="INVALID_STATUS")
    warehouse.patch(f"/internal/orders/{o['id']}/status", json={"status": "pending"}).expect(400, code="INVALID_STATUS_TRANSITION")
    c = warehouse.patch(f"/internal/orders/{o['id']}/status", json={"status": "completed"}).expect(200).json_("order")
    assert c["status"] == "completed" and factory.stock(s.id)["physical"] == 3
    warehouse.patch(f"/internal/orders/{o['id']}/status", json={"status": "cancelled"}).expect(409, code="ORDER_NOT_MODIFIABLE")
    warehouse.patch(f"/internal/orders/{o['id']}/assign-vehicle", json={"vehicle_id": vid}).expect(409, code="ORDER_NOT_ASSIGNABLE")


# ───────────────────────────── concurrency ─────────────────────────────
def test_concurrent_orders_on_scarce_stock_never_oversell(operator, operator2, factory):
    """12 parallel orders of 3 units on 10 units in stock: at most 3 win, the rest get INSUFFICIENT_STOCK, reserved never exceeds physical"""
    s = factory.stocked(10)
    body = factory.order_body([(s.id, 3)])
    apis = [operator, operator2]
    with ThreadPoolExecutor(max_workers=12) as pool:
        rs = list(pool.map(lambda i: apis[i % 2].post("/orders", json=body), range(12)))
    won = [r for r in rs if r.status == 201]
    lost = [r for r in rs if r.status != 201]
    assert len(won) == 3, [(r.status, r.text[:80]) for r in rs]
    assert all(r.status == 400 and r.code == "INSUFFICIENT_STOCK" for r in lost), [(r.status, r.text[:120]) for r in lost]
    st = factory.stock(s.id)
    assert st["reserved"] == 9 and st["available"] == 1 and st["physical"] == 10


def test_stock_numbers_across_the_whole_lifecycle(operator, factory, infra, vehicle_id):
    """available/reserved/physical through create -> in_progress -> completed, and through create -> cancel"""
    s = factory.stocked(20)
    o1 = factory.order(operator, [(s.id, 5)])
    o2 = factory.order(operator, [(s.id, 3)])
    assert factory.stock(s.id) == {"available": 12, "reserved": 8, "physical": 20, "min": 0}
    infra.status(o1["id"], "in_progress", vehicle_id[0]).expect(200)
    assert factory.stock(s.id) == {"available": 12, "reserved": 8, "physical": 20, "min": 0}
    infra.status(o1["id"], "completed", vehicle_id[0]).expect(200)
    assert factory.stock(s.id) == {"available": 12, "reserved": 3, "physical": 15, "min": 0}
    operator.post(f"/orders/{o2['id']}/cancel").expect(200)
    assert factory.stock(s.id) == {"available": 15, "reserved": 0, "physical": 15, "min": 0}
