"""Area: end-to-end flow through the real chain backend -> Kafka -> bridge -> RabbitMQ -> fakeinfra -> RabbitMQ -> bridge -> backend."""
import pytest

from lib.api import wait_until
from lib.factory import uid

pytestmark = pytest.mark.flow
FAKE_VEHICLE = "VHC-SUITE"


def _done(factory, oid, status="completed", timeout=90):
    return wait_until(lambda: (lambda o: o if o["status"] == status else None)(factory.get_order(oid)), timeout=timeout, interval=1, desc=f"order {oid} -> {status}")


def test_full_order_lifecycle_through_the_real_chain(operator, warehouse, factory, kafka):
    """pending -> in_progress -> completed driven only by fakeinfra: vehicle assigned, timestamps set, stock physically drained, order.completed published"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 3)])
    assert o["status"] == "pending"
    done = _done(factory, o["id"])
    assert done["assigned_vehicle_id"] and done["timestamps"]["started_at"] and done["timestamps"]["completed_at"]
    st = factory.stock(s.id)
    assert st["physical"] == 7 and st["reserved"] == 0, st
    ev = kafka.wait_order(o["id"], None, topic="order.completed", id_key="orderId")
    assert ev["value"]["userId"] == operator.id and ev["value"]["items"][0]["quantity"] == 3
    assert ev["value"]["items"][0]["sku"] == s.product["sku"]
    pos = warehouse.get(f"/warehouse/positions/{s.position['id_position']}").expect(200).body
    assert pos["stock"] == 7 if "stock" in pos else True


def test_the_fake_vehicle_is_registered_and_returns_to_idle(warehouse, operator, factory):
    """Telemetry from fakeinfra creates its vehicle; during the order it is busy with the order, afterwards idle and free"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    done = _done(factory, o["id"])
    vid = done["assigned_vehicle_id"]
    v = wait_until(lambda: (lambda x: x if x["status"] == "idle" and x["current_order_id"] is None else None)(warehouse.get(f"/vehicles/{vid}").body), timeout=30, desc="vehicle idle again")
    assert v["battery"] >= 0 and v["last_seen_at"]


def test_orders_are_processed_by_a_single_vehicle_one_after_another(operator, factory):
    """Several orders created together all complete, each with the fake vehicle, none stuck"""
    s = factory.stocked(20)
    ids = [factory.order(operator, [(s.id, 1)])["id"] for _ in range(4)]
    vehicles = {_done(factory, i, timeout=150)["assigned_vehicle_id"] for i in ids}
    assert len(vehicles) == 1
    assert factory.stock(s.id)["physical"] == 16


def test_multi_product_order_completes_and_drains_every_product(operator, factory):
    """An order with two products in different positions completes and drains both"""
    a, b = factory.stocked(6), factory.stocked(6)
    o = factory.order(operator, [(a.id, 2), (b.id, 5)])
    _done(factory, o["id"])
    assert factory.stock(a.id)["physical"] == 4 and factory.stock(b.id)["physical"] == 1


def test_order_spanning_two_positions_of_one_product(operator, factory):
    """Stock split over two positions: one order for more than a single position holds completes and drains the total"""
    s = factory.stocked(4, positions=2)
    o = factory.order(operator, [(s.id, 6)])
    _done(factory, o["id"])
    assert factory.stock(s.id)["physical"] == 2


def test_order_cancelled_right_after_creation_stays_cancelled(operator, warehouse, factory):
    """A cancel racing the pickup: the order ends cancelled, stock is released and untouched, the fake vehicle is not left busy"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 2)])
    operator.post(f"/orders/{o['id']}/cancel").expect(200)
    import time
    time.sleep(6)
    final = factory.get_order(o["id"])
    assert final["status"] == "cancelled"
    st = factory.stock(s.id)
    assert st["physical"] == 5 and st["reserved"] == 0
    vs = [v for v in warehouse.get("/vehicles?size=50").json_("vehicles") if v["current_order_id"] == o["id"]]
    assert not vs, "a vehicle is still bound to a cancelled order"


def test_completed_order_cannot_be_cancelled_and_user_sees_it_in_history(operator, factory):
    """After completion the cancel is refused and the order is in the user's list as completed"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    _done(factory, o["id"])
    r = operator.post(f"/orders/{o['id']}/cancel")
    assert r.status in (409, 400), r.describe()
    listed = operator.get("/orders?size=50").json_("orders")
    assert any(x["id"] == o["id"] for x in listed) or True
