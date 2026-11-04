"""Area: failure modes of the Infra side (docs/INFRA_CONTRACT.md), played by the fake Infra's control channel.

The fake Infra follows the real central: a cancel of an order on the road makes the vehicle drive back and
reports NOTHING about the order, the vehicle is only free again when its telemetry says `idle`.
"""
import time

import pytest

from lib.api import wait_until
from lib.factory import InfraClient

pytestmark = pytest.mark.flow
FAKE_VEHICLE = "VHC-SUITE"
VEHICLE_ID = InfraClient.backend_vehicle_id(FAKE_VEHICLE)
NORMAL = {"drop_cancels": False, "duplicate_statuses": False, "stuck_busy": False, "step_delay": "1s", "restart": True}


@pytest.fixture
def control(rabbit, fakeinfra):
    """Flips fake-Infra modes; restores the defaults (and forgets any order on the road) afterwards."""
    def send(**msg):
        assert rabbit.publish("fakeinfra.control", msg), "fakeinfra.control is not bound: is the fake Infra running?"
        time.sleep(0.5)
    send(**NORMAL)
    yield send
    send(**NORMAL)


def _vehicle(warehouse):
    return warehouse.get(f"/vehicles/{VEHICLE_ID}").expect(200).body


def _wait_vehicle(warehouse, status, timeout=60):
    return wait_until(lambda: (lambda v: v if v["status"] == status else None)(_vehicle(warehouse)), timeout=timeout, desc=f"vehicle {status}")


def _in_progress(factory, oid, timeout=60):
    return wait_until(lambda: (lambda o: o if o["status"] == "in_progress" else None)(factory.get_order(oid)), timeout=timeout, desc="in_progress")


def test_cancel_of_an_order_on_the_road_is_immediate_and_the_vehicle_frees_itself_on_its_own(operator, warehouse, factory, control):
    """In-progress cancel: the order is cancelled and stock released at once; Infra reports no order status; the vehicle is busy while driving back and idle after"""
    control(step_delay="4s")
    s = factory.stocked(5, positions=2)
    o = factory.order(operator, [(s.id, 7)])  # spans both positions: two stops
    _in_progress(factory, o["id"])
    _wait_vehicle(warehouse, "busy")
    assert operator.post(f"/orders/{o['id']}/cancel", json={"reason": "e2e"}).expect(200).body
    after = factory.get_order(o["id"])
    assert after["status"] == "cancelled"
    st = factory.stock(s.id)
    assert st["physical"] == 10 and st["reserved"] == 0, st
    assert _vehicle(warehouse)["current_order_id"] is None, "the order pointer must clear at cancel time"
    # Infra drives the vehicle back; the backend follows the telemetry, not the cancel.
    _wait_vehicle(warehouse, "idle", timeout=90)
    time.sleep(3)
    final = factory.get_order(o["id"])
    assert final["status"] == "cancelled" and factory.stock(s.id)["physical"] == 10


def test_a_cancel_that_never_reaches_infra_leaves_the_order_cancelled_and_stock_untouched(operator, warehouse, factory, control):
    """Lost cancel: Infra finishes the trip and reports completed; the backend refuses it (409, discarded by the bridge) and does not drain stock"""
    control(drop_cancels=True, step_delay="2s")
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 2)])
    _in_progress(factory, o["id"])
    operator.post(f"/orders/{o['id']}/cancel").expect(200)
    _wait_vehicle(warehouse, "idle", timeout=90)  # the trip ended, Infra freed the vehicle
    time.sleep(2)
    final = factory.get_order(o["id"])
    assert final["status"] == "cancelled"
    st = factory.stock(s.id)
    assert st["physical"] == 5 and st["reserved"] == 0, f"goods moved by the rover but stock was not drained: {st}"
    assert _vehicle(warehouse)["current_order_id"] is None


def test_duplicate_statuses_from_infra_complete_the_order_once(operator, factory, control):
    """Every status published twice (resend after a restart): one transition, stock drained once"""
    control(duplicate_statuses=True)
    s = factory.stocked(9)
    o = factory.order(operator, [(s.id, 4)])
    done = wait_until(lambda: (lambda x: x if x["status"] == "completed" else None)(factory.get_order(o["id"])), timeout=90, desc="completed")
    time.sleep(2)
    assert factory.stock(s.id)["physical"] == 5, "a duplicated completed drained the stock twice"
    assert done["assigned_vehicle_id"] == VEHICLE_ID


def test_infra_restart_mid_order_leaves_the_order_in_progress_until_an_admin_cancels_it(operator, warehouse, factory, control):
    """Infra restarts and forgets the order: the backend keeps it in_progress (no timeout), the vehicle reads idle, an admin cancel cleans everything"""
    control(step_delay="5s")
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    _in_progress(factory, o["id"])
    control(restart=True)  # the central comes back with no memory of the order
    _wait_vehicle(warehouse, "idle")
    time.sleep(8)
    assert factory.get_order(o["id"])["status"] == "in_progress", "documented gap: nothing times an orphaned in_progress order out"
    warehouse.post(f"/orders/{o['id']}/cancel", json={"reason": "orphaned"}).expect(200)
    st = factory.stock(s.id)
    assert st["physical"] == 5 and st["reserved"] == 0
    assert _vehicle(warehouse)["current_order_id"] is None


def test_a_vehicle_that_never_reports_idle_stays_busy_without_an_order_and_recovers_with_the_next_idle_reading(operator, warehouse, factory, control, infra):
    """Stuck busy (Infra never says idle): the backend does NOT guess; /vehicles shows busy with current_order_id=null, last_seen_at moves; one idle reading frees it"""
    control(stuck_busy=True, step_delay="1s")
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    wait_until(lambda: (lambda x: x if x["status"] == "completed" else None)(factory.get_order(o["id"])), timeout=90, desc="completed")
    stuck = _wait_vehicle(warehouse, "busy")
    assert stuck["current_order_id"] is None, stuck
    assert stuck["last_seen_at"]
    infra.telemetry(FAKE_VEHICLE, status="idle", battery=70).expect(200)
    assert _vehicle(warehouse)["status"] == "idle"
