"""Area: manual stock adjustments and order reconciliation (docs/STOCK_ADJUSTMENTS.md, PR #150)."""
from concurrent.futures import ThreadPoolExecutor

import pytest

from lib.api import wait_until
from lib.factory import uid


def _adjust(api, position_id, quantity, **extra):
    return api.post(f"/warehouse/positions/{position_id}/stock-adjustments", json={"quantity": quantity, **extra})


def _spec_scenario(factory, operator, infra, vehicle_id, extra_item=None):
    """14 units; an in-progress order of 5, then pending orders asking 4 (older) and 2 (newer)."""
    s = factory.stocked(14)
    infra_vid, _ = vehicle_id
    in_proc = factory.order(operator, [(s.id, 5)])
    infra.status(in_proc["id"], "in_progress", infra_vid).expect(200)
    old4 = factory.order(operator, [(s.id, 4)])
    new2 = factory.order(operator, [(s.id, 2)])
    return s, in_proc, old4, new2


def test_spec_example_via_adjustment_endpoint(warehouse, operator, factory, infra, vehicle_id, kafka):
    """14 units, 6 break -> 8 left: in-process 5 respected, oldest pending (4) cancelled with INSUFFICIENT_STOCK, next (2) kept"""
    s, in_proc, old4, new2 = _spec_scenario(factory, operator, infra, vehicle_id)
    assert factory.stock(s.id) == {"available": 3, "reserved": 11, "physical": 14, "min": 0}
    r = _adjust(warehouse, s.position["id_position"], 6, type="DAMAGE", reason="Cajas rotas").expect(201).body
    adj = r["adjustment"]
    assert (adj["type"], adj["quantity_before"], adj["quantity_after"], adj["reason"]) == ("DAMAGE", 14, 8, "Cajas rotas")
    assert adj["position_id"] == s.position["id_position"] and adj["product_id"] == s.id
    assert adj["adjusted_by"] == warehouse.id and adj["cancelled_order_ids"] == [old4["id"]]
    assert [c["id"] for c in r["cancelled_orders"]] == [old4["id"]]
    assert r["cancelled_orders"][0]["cancel_reason"] == f"INSUFFICIENT_STOCK: product {s.product['sku']} needs 4 units but only 3 remain available"
    assert factory.get_order(old4["id"])["status"] == "cancelled"
    assert factory.get_order(in_proc["id"])["status"] == "in_progress"
    assert factory.get_order(new2["id"])["status"] == "pending"
    assert factory.get_order(old4["id"])["cancel_reason"].startswith("INSUFFICIENT_STOCK: ")
    assert factory.stock(s.id) == {"available": 1, "reserved": 7, "physical": 8, "min": 0}
    msg = kafka.wait_order(old4["id"], "cancelled")["value"]
    assert msg["status"] == "cancelled" and msg["order_id"] == old4["id"]


def test_spec_example_via_patch_current_stock(warehouse, operator, factory, infra, vehicle_id):
    """The same reconciliation is triggered by PATCH current_stock 14 -> 8; the response carries cancelled_orders"""
    s, in_proc, old4, new2 = _spec_scenario(factory, operator, infra, vehicle_id)
    r = warehouse.patch(f"/warehouse/positions/{s.position['id_position']}", json={"current_stock": 8, "reason": "recount"}).expect(200).body
    assert r["current_stock"] == 8 and [c["id"] for c in r["cancelled_orders"]] == [old4["id"]]
    hist = warehouse.get(f"/warehouse/positions/{s.position['id_position']}/stock-adjustments").expect(200).json_("stock_adjustments")
    assert hist[0]["type"] == "MANUAL_DECREASE" and hist[0]["reason"] == "recount" and (hist[0]["quantity_before"], hist[0]["quantity_after"]) == (14, 8)
    assert factory.get_order(new2["id"])["status"] == "pending"


def test_patch_without_reduction_returns_empty_cancelled_orders(warehouse, factory):
    """PATCH always carries cancelled_orders (empty list when nothing was cancelled)"""
    s = factory.stocked(5)
    r = warehouse.patch(f"/warehouse/positions/{s.position['id_position']}", json={"position_name": "renamed"}).expect(200).body
    assert r["cancelled_orders"] == []


def test_increase_is_recorded_and_cancels_nothing(warehouse, operator, factory):
    """Raising stock by PATCH records MANUAL_INCREASE and never cancels orders"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 9)])
    r = warehouse.patch(f"/warehouse/positions/{s.position['id_position']}", json={"current_stock": 20}).expect(200).body
    assert r["cancelled_orders"] == []
    h = warehouse.get(f"/products/{s.id}/stock-adjustments").expect(200).json_("stock_adjustments")
    assert h[0]["type"] == "MANUAL_INCREASE" and (h[0]["quantity_before"], h[0]["quantity_after"]) == (10, 20)
    assert factory.get_order(o["id"])["status"] == "pending"


def test_adjustment_default_type_and_reason_optional(warehouse, factory):
    """type defaults to MANUAL_DECREASE; reason is optional (null)"""
    s = factory.stocked(10)
    adj = _adjust(warehouse, s.position["id_position"], 3).expect(201).json_("adjustment")
    assert adj["type"] == "MANUAL_DECREASE" and adj["reason"] is None and adj["cancelled_order_ids"] == []
    assert warehouse.get(f"/warehouse/positions/{s.position['id_position']}").json_("current_stock") == 7


@pytest.mark.parametrize("quantity,status,code", [
    (0, 400, "VALIDATION_ERROR"), (-3, 400, "VALIDATION_ERROR"), (2.5, 400, "VALIDATION_ERROR"),
    ("abc", 400, "MALFORMED_REQUEST"), (None, 400, "VALIDATION_ERROR"), (11, 400, "QUANTITY_EXCEEDS_POSITION_STOCK"),
])
def test_adjustment_quantity_must_be_a_whole_number_within_stock(warehouse, factory, quantity, status, code):
    """quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated"""
    s = factory.stocked(10)
    pid = s.position["id_position"]
    before = warehouse.get(f"/warehouse/positions/{pid}/stock-adjustments").json_("pagination", "total_elements")  # the setup PATCH is in the history
    _adjust(warehouse, pid, quantity).expect(status, code=code)
    assert warehouse.get(f"/warehouse/positions/{pid}").json_("current_stock") == 10, "a rejected adjustment changes nothing"
    assert warehouse.get(f"/warehouse/positions/{pid}/stock-adjustments").json_("pagination", "total_elements") == before, "...and records nothing"


def test_adjustment_type_and_position_rules(warehouse, factory):
    """MANUAL_INCREASE/other types are not accepted here; position without product 400; unknown position 404"""
    s = factory.stocked(10)
    pid = s.position["id_position"]
    _adjust(warehouse, pid, 1, type="MANUAL_INCREASE").expect(400, code="INVALID_ADJUSTMENT_TYPE")
    _adjust(warehouse, pid, 1, type="UNASSIGNED").expect(400, code="INVALID_ADJUSTMENT_TYPE")
    _adjust(warehouse, pid, 1, type="EXPLODED").expect(400)
    _adjust(warehouse, pid, 1, reason="x" * 501).expect(400, code="VALIDATION_ERROR")
    empty = factory.position(s.line["id_line"])
    _adjust(warehouse, empty["id_position"], 1).expect(400, code="POSITION_WITHOUT_PRODUCT")
    _adjust(warehouse, "nope", 1).expect(404, code="POSITION_NOT_FOUND")


def test_adjustment_ignores_minimum_and_reserved_stock(warehouse, operator, factory):
    """The adjustment can take stock below the product minimum and below what is reserved (the orders are then cancelled)"""
    s = factory.stocked(10, minimum_stock=8)
    o = factory.order(operator, [(s.id, 6)])
    r = _adjust(warehouse, s.position["id_position"], 9).expect(201).body
    assert [c["id"] for c in r["cancelled_orders"]] == [o["id"]]
    assert factory.stock(s.id)["physical"] == 1


def test_whole_multi_product_order_is_cancelled_and_releases_other_reservations(warehouse, operator, factory):
    """A pending order that no longer fits is cancelled entirely, including its other products"""
    a, b = factory.stocked(10), factory.stocked(10)
    o = factory.order(operator, [(a.id, 8), (b.id, 5)])
    assert factory.stock(b.id)["reserved"] == 5
    _adjust(warehouse, a.position["id_position"], 5).expect(201)
    assert factory.get_order(o["id"])["status"] == "cancelled"
    assert factory.stock(b.id) == {"available": 10, "reserved": 0, "physical": 10, "min": 0}


def test_orders_of_other_products_are_untouched(warehouse, operator, factory):
    """Reconciliation looks only at the adjusted product's orders"""
    a, b = factory.stocked(10), factory.stocked(10)
    ob = factory.order(operator, [(b.id, 10)])
    _adjust(warehouse, a.position["id_position"], 10).expect(201)
    assert factory.get_order(ob["id"])["status"] == "pending"


def test_inactive_position_adjustment_is_recorded_without_reconciling(warehouse, operator, factory):
    """Lowering an inactive position is recorded, but its stock never counted so no order is cancelled"""
    s = factory.stocked(10, positions=2)
    p0, p1 = (p["id_position"] for p in s.positions)
    o = factory.order(operator, [(s.id, 10)])
    warehouse.patch(f"/warehouse/positions/{p1}", json={"is_active": False}).expect(200)  # cancels nothing: 10 left in p0
    assert factory.get_order(o["id"])["status"] == "pending"
    r = _adjust(warehouse, p1, 10).expect(201).body
    assert r["cancelled_orders"] == [] and r["adjustment"]["quantity_after"] == 0
    assert factory.get_order(o["id"])["status"] == "pending"


def test_deactivating_or_deleting_a_position_reconciles(warehouse, operator, factory):
    """is_active=false and DELETE on a stocked position record DEACTIVATED and cancel what no longer fits"""
    for how in ("patch", "delete"):
        s = factory.stocked(10)
        pid = s.position["id_position"]
        o = factory.order(operator, [(s.id, 7)])
        if how == "patch":
            r = warehouse.patch(f"/warehouse/positions/{pid}", json={"is_active": False}).expect(200).body
            assert [c["id"] for c in r["cancelled_orders"]] == [o["id"]]
        else:
            warehouse.delete(f"/warehouse/positions/{pid}").expect(204)
        assert factory.get_order(o["id"])["status"] == "cancelled", how
        h = warehouse.get(f"/warehouse/positions/{pid}/stock-adjustments").json_("stock_adjustments")
        assert h[0]["type"] == "DEACTIVATED" and h[0]["cancelled_order_ids"] == [o["id"]], how


def test_unassigning_the_product_reconciles(warehouse, operator, factory):
    """PATCH unassign_product with stock records UNASSIGNED and cancels the pending orders"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 3)])
    r = warehouse.patch(f"/warehouse/positions/{s.position['id_position']}", json={"unassign_product": True}).expect(200).body
    assert [c["id"] for c in r["cancelled_orders"]] == [o["id"]]
    h = warehouse.get(f"/products/{s.id}/stock-adjustments").json_("stock_adjustments")
    assert h[0]["type"] == "UNASSIGNED" and (h[0]["quantity_before"], h[0]["quantity_after"]) == (10, 0)


def test_delete_product_cancels_what_cannot_be_covered_but_spares_in_progress(warehouse, operator, factory, infra, vehicle_id):
    """DELETE product: pending orders are cancelled, the in-progress one is respected; UNASSIGNED entry says 'Product deleted'"""
    s = factory.stocked(10)
    inflight = factory.order(operator, [(s.id, 2)])
    infra.status(inflight["id"], "in_progress", vehicle_id[0]).expect(200)
    p1, p2 = factory.order(operator, [(s.id, 3)]), factory.order(operator, [(s.id, 1)])
    warehouse.delete(f"/products/{s.id}").expect(204)
    assert factory.get_order(p1["id"])["status"] == "cancelled" and factory.get_order(p2["id"])["status"] == "cancelled"
    assert factory.get_order(p1["id"])["cancel_reason"].startswith("INSUFFICIENT_STOCK: ")
    assert factory.get_order(inflight["id"])["status"] == "in_progress"
    h = warehouse.get(f"/products/{s.id}/stock-adjustments").expect(200)  # history stays readable after deletion? (404 is also defensible)
    assert h.json_("stock_adjustments")[0]["reason"] == "Product deleted"


def test_adjustment_history_endpoints_order_pagination_and_roles(warehouse, admin, sales, operator, dispatcher, factory):
    """History: newest first, paginated; position history for WAREHOUSE/SUPERADMIN, product history also for SALES; 404s"""
    s = factory.stocked(20, positions=2)  # setup leaves 2 MANUAL_INCREASE entries (one per position)
    p0, p1 = (p["id_position"] for p in s.positions)
    for pid, q in ((p0, 1), (p1, 2), (p0, 3), (p1, 4)):
        _adjust(warehouse, pid, q).expect(201)
    prod_hist = sales.get(f"/products/{s.id}/stock-adjustments?size=3").expect(200)
    assert [a["quantity_before"] - a["quantity_after"] for a in prod_hist.json_("stock_adjustments")] == [4, 3, 2], "newest first"
    assert prod_hist.json_("pagination", "total_elements") == 6 and prod_hist.json_("pagination", "total_pages") == 2
    page2 = admin.get(f"/products/{s.id}/stock-adjustments?size=3&page=1").json_("stock_adjustments")
    assert [a["type"] for a in page2[1:]] == ["MANUAL_INCREASE", "MANUAL_INCREASE"] and page2[0]["quantity_before"] - page2[0]["quantity_after"] == 1
    pos_hist = admin.get(f"/warehouse/positions/{p0}/stock-adjustments").json_("stock_adjustments")
    assert [a["position_id"] for a in pos_hist] == [p0, p0, p0]
    sales.get(f"/warehouse/positions/{p0}/stock-adjustments").expect(403)
    operator.get(f"/products/{s.id}/stock-adjustments").expect(403)
    dispatcher.get(f"/warehouse/positions/{p0}/stock-adjustments").expect(403)
    sales.post(f"/warehouse/positions/{p0}/stock-adjustments", json={"quantity": 1}).expect(403)
    warehouse.get("/products/nope/stock-adjustments").expect(404, code="PRODUCT_NOT_FOUND")
    warehouse.get("/warehouse/positions/nope/stock-adjustments").expect(404, code="POSITION_NOT_FOUND")


def test_concurrent_adjustments_of_different_positions_conflict_cleanly(warehouse, operator, factory):
    """Simultaneous adjustments of one product's different positions: each answers 201 or 409 STOCK_CHANGE_CONFLICT, never 5xx, and the numbers add up"""
    s = factory.stocked(50, positions=4)
    factory.order(operator, [(s.id, 5)])  # something to reconcile against
    pids = [p["id_position"] for p in s.positions]
    ok = conflicts = 0
    for _ in range(4):  # rounds of 4 parallel requests, one per position
        with ThreadPoolExecutor(max_workers=4) as pool:
            results = list(pool.map(lambda i: _adjust(warehouse, pids[i], 1), range(4)))
        bad = [r for r in results if r.status not in (201, 409)]
        assert not bad, "unexpected statuses: " + "; ".join(f"{r.status} {r.text[:200]}" for r in bad)
        assert all(r.code == "STOCK_CHANGE_CONFLICT" for r in results if r.status == 409)
        ok += sum(r.status == 201 for r in results)
        conflicts += sum(r.status == 409 for r in results)
    assert ok, "at least one adjustment must win"
    assert factory.stock(s.id)["physical"] == 200 - ok, "stock lowered exactly by the successful adjustments"
    total = warehouse.get(f"/products/{s.id}/stock-adjustments?size=50").json_("pagination", "total_elements")
    assert total == 4 + ok, "one history entry per successful adjustment (+4 setup entries)"
    _adjust(warehouse, pids[0], 1).expect(201)  # a conflict is retryable: nothing was changed by it


def test_concurrent_adjustments_of_the_same_position_conflict_cleanly(warehouse, factory):
    """Parallel adjustments of one position never 500: they answer 201 or 409 STOCK_CHANGE_CONFLICT"""
    s = factory.stocked(100)
    pid = s.position["id_position"]
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(lambda _: _adjust(warehouse, pid, 1), range(8)))
    bad = [r for r in results if r.status not in (201, 409)]
    assert not bad, "unexpected statuses: " + "; ".join(f"{r.status} {r.text[:160]}" for r in bad)
    won = sum(r.status == 201 for r in results)
    assert warehouse.get(f"/warehouse/positions/{pid}").json_("current_stock") == 100 - won


def test_cancellation_is_published_only_for_committed_changes(warehouse, operator, factory, kafka):
    """A rejected adjustment publishes no cancellation; a committed one publishes exactly one cancelled message per order"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 10)])
    _adjust(warehouse, s.position["id_position"], 99).expect(400)
    assert not kafka.for_order(o["id"], "cancelled")
    _adjust(warehouse, s.position["id_position"], 1).expect(201)
    kafka.wait_order(o["id"], "cancelled")
    wait_until(lambda: len(kafka.for_order(o["id"], "cancelled")) == 1, desc="exactly one cancelled message")
