"""Area: WebSocket feeds (/ws/v1/orders, /ws/v1/orders/{userId}, /ws/v1/vehicles, /ws/v1/stock/alerts)."""
import time

import jwt
import pytest

from lib import config
from lib.factory import uid
from lib.ws import feed, handshake_status


def _expired(user_id: str, role: str) -> str:
    now = int(time.time())
    return jwt.encode({"sub": user_id, "email": "x@x.x", "role": role, "iat": now - 100, "exp": now - 50}, config.JWT_SECRET, algorithm="HS512")


# ───────────────────────────── handshake / authorization ─────────────────────────────
FEEDS = {
    "/ws/v1/orders": {"SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES"},
    "/ws/v1/vehicles": {"SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE"},
    "/ws/v1/stock/alerts": {"SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES"},
}


@pytest.mark.parametrize("path", FEEDS)
def test_ws_handshake_role_matrix(role_apis, path):
    """Each feed accepts its documented roles (101) and refuses the others (403)"""
    bad = []
    for role, api in role_apis.items():
        status = handshake_status(path, api.token)
        want = 101 if role in FEEDS[path] else 403
        if status != want:
            bad.append(f"{role}: expected {want}, got {status}")
    assert not bad, f"{path}: " + "; ".join(bad)


@pytest.mark.parametrize("path", [*FEEDS, "/ws/v1/orders/someone"])
def test_ws_handshake_rejects_missing_invalid_and_expired_tokens(admin, path):
    """No token, garbage token, token signed with another secret and an expired token are all refused (403)"""
    forged = jwt.encode({"sub": admin.id, "role": "SUPERADMIN", "exp": int(time.time()) + 600}, "z" * 64, algorithm="HS512")
    for tok in (None, "", "garbage", forged, _expired(admin.id, "SUPERADMIN")):
        assert handshake_status(path, tok) == 403, f"token={tok!r}"


def test_ws_user_feed_only_for_the_owner(operator, operator2, admin):
    """/ws/v1/orders/{userId} accepts a token whose subject is that user and refuses anyone else, admins included"""
    assert handshake_status(f"/ws/v1/orders/{operator.id}", operator.token) == 101
    assert handshake_status(f"/ws/v1/orders/{operator.id}", operator2.token) == 403
    assert handshake_status(f"/ws/v1/orders/{operator.id}", admin.token) == 403


# ───────────────────────────── events ─────────────────────────────
def test_order_events_reach_the_orders_feed(admin, operator, warehouse, factory):
    """Creating and cancelling an order pushes order.updated events (full order payload) to /ws/v1/orders"""
    s = factory.stocked(5)
    with feed("/ws/v1/orders", admin.token) as f:
        o = factory.order(operator, [(s.id, 2)])
        created = f.wait(lambda e: e.get("event") == "order.updated" and e["payload"]["id"] == o["id"])
        assert created["payload"]["status"] == "pending" and created["payload"]["items"][0]["quantity"] == 2
        assert created["payload"]["requested_by_user_id"] == operator.id
        operator.post(f"/orders/{o['id']}/cancel", json={"reason": "ws"}).expect(200)
        f.wait(lambda e: e.get("event") == "order.updated" and e["payload"]["id"] == o["id"] and e["payload"]["status"] == "cancelled")


def test_orders_feed_fans_out_to_every_client_and_survives_disconnects(admin, system, operator, factory):
    """Two simultaneous clients both get the event; one leaving does not stop delivery to the other"""
    s = factory.stocked(5)
    with feed("/ws/v1/orders", admin.token) as f1:
        with feed("/ws/v1/orders", system.token) as f2:
            o1 = factory.order(operator, [(s.id, 1)])
            f1.wait(lambda e: e.get("payload", {}).get("id") == o1["id"])
            f2.wait(lambda e: e.get("payload", {}).get("id") == o1["id"])
        o2 = factory.order(operator, [(s.id, 1)])  # f2 is gone
        f1.wait(lambda e: e.get("payload", {}).get("id") == o2["id"])


def test_user_feed_delivers_only_the_users_own_orders(operator, operator2, factory):
    """/ws/v1/orders/{userId} receives the user's own order events and none of other users' orders"""
    s = factory.stocked(10)
    with feed(f"/ws/v1/orders/{operator.id}", operator.token) as f:
        other = factory.order(operator2, [(s.id, 1)])
        mine = factory.order(operator, [(s.id, 1)])
        ev = f.wait(lambda e: e.get("event") == "order.updated" and e["payload"]["id"] == mine["id"])
        assert ev["payload"]["requested_by_user_id"] == operator.id
        f.pump(1.0)
        assert not [e for e in f.events if e.get("payload", {}).get("id") == other["id"]], "another user's order leaked into this feed"


def test_stock_reconciliation_cancellations_are_pushed(admin, operator, warehouse, factory):
    """Orders cancelled by a stock adjustment are announced on the orders feed (after the commit)"""
    s = factory.stocked(10)
    o = factory.order(operator, [(s.id, 10)])
    with feed("/ws/v1/orders", admin.token) as f:
        warehouse.post(f"/warehouse/positions/{s.position['id_position']}/stock-adjustments", json={"quantity": 1}).expect(201)
        ev = f.wait(lambda e: e.get("payload", {}).get("id") == o["id"])
        assert ev["payload"]["status"] == "cancelled" and ev["payload"]["cancel_reason"].startswith("INSUFFICIENT_STOCK")


def test_vehicle_events_on_telemetry_and_faults(admin, infra, vehicle_id):
    """Telemetry pushes vehicle.updated; a reading carrying an error also pushes vehicle.error with the fault"""
    infra_vid, vid = vehicle_id
    with feed("/ws/v1/vehicles", admin.token) as f:
        infra.telemetry(infra_vid, "busy", 66).expect(200)
        up = f.wait(lambda e: e.get("event") == "vehicle.updated" and e["payload"]["id"] == vid)
        assert up["payload"]["status"] == "busy" and up["payload"]["battery"] == 66
        infra.telemetry(infra_vid, "error", 60, error={"code": "MOTOR_STALL", "message": "stalled"}).expect(200)
        err = f.wait(lambda e: e.get("event") == "vehicle.error" and vid in str(e["payload"]))
        assert "MOTOR_STALL" in str(err["payload"]) or "stalled" in str(err["payload"])


def test_stock_alert_when_an_order_leaves_the_product_below_its_minimum(admin, operator, infra, factory, vehicle_id):
    """Completing an order that leaves physical stock under the product minimum pushes stock.alert on /ws/v1/stock/alerts"""
    s = factory.stocked(10, minimum_stock=8)
    o = factory.order(operator, [(s.id, 5)])
    with feed("/ws/v1/stock/alerts", admin.token) as f:
        infra.status(o["id"], "in_progress", vehicle_id[0]).expect(200)
        infra.status(o["id"], "completed", vehicle_id[0]).expect(200)
        ev = f.wait(lambda e: e.get("event") == "stock.alert" and s.id in str(e["payload"]))
        assert 5 in ev["payload"].values() and ev["payload"].get("sku", s.product["sku"]) == s.product["sku"], ev


def test_no_stock_alert_while_stock_stays_above_the_minimum(admin, operator, infra, factory, vehicle_id):
    """No alert when the remaining stock is still at or above the minimum"""
    s = factory.stocked(10, minimum_stock=3)
    o = factory.order(operator, [(s.id, 2)])
    with feed("/ws/v1/stock/alerts", admin.token) as f:
        infra.status(o["id"], "in_progress", vehicle_id[0]).expect(200)
        infra.status(o["id"], "completed", vehicle_id[0]).expect(200)
        f.pump(1.5)
        assert not [e for e in f.events if s.id in str(e)], f.events


def test_ws_ignores_client_messages(admin):
    """Feeds are server-push only: frames sent by the client are ignored and the socket stays open"""
    with feed("/ws/v1/orders", admin.token) as f:
        f.ws.send("hello")
        f.ws.send('{"event":"noop"}')
        f.pump(0.5)
        f.ws.ping().wait(5)
