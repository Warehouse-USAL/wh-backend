"""Area: bridge (Kafka `orders` -> RabbitMQ -> Infra, and Infra's RabbitMQ messages -> backend HTTP callbacks)."""
import json
import time
import uuid

import httpx

from lib import config
from lib.api import wait_until
from lib.factory import uid


def _publish_vehicle_msg(rabbit, payload):
    assert rabbit.publish("vehicles", payload), "message to amq.topic key=vehicles was not routed: the bridge queue is missing"


def test_bridge_health_endpoint():
    """GET /healthz on the bridge answers 200 ok"""
    r = httpx.get(f"{config.BRIDGE_HEALTH}/healthz", timeout=5)
    assert r.status_code == 200 and r.text == "ok"


def test_order_reaches_rabbitmq_exactly_as_published_on_kafka(operator, factory, kafka, rabbit):
    """Kafka `orders` -> amq.topic key `orders`: same JSON, persistent, application/json, pending then cancelled"""
    s = factory.stocked(5)
    o = factory.order(operator, [(s.id, 1)])
    kmsg = kafka.wait_order(o["id"], "pending")
    amqp = rabbit.wait_order(o["id"], "pending")
    assert amqp["exchange"] == "amq.topic" and amqp["routing_key"] == "orders"
    assert amqp["body"] == kmsg["value"], "the bridge republishes the message untouched"
    assert amqp["props"].get("delivery_mode") == 2 and amqp["props"].get("content_type") == "application/json"
    operator.post(f"/orders/{o['id']}/cancel").expect(200)
    assert rabbit.wait_order(o["id"], "cancelled")["body"]["stops"] == kmsg["value"]["stops"]


def test_each_order_is_delivered_once_and_in_order(operator, factory, rabbit):
    """A burst of orders arrives in RabbitMQ complete, without duplicates, preserving creation order"""
    s = factory.stocked(50)
    ids = [factory.order(operator, [(s.id, 1)])["id"] for _ in range(8)]
    for oid in ids:
        rabbit.wait_order(oid, "pending")
    seen = [m["body"]["order_id"] for m in rabbit.seen if isinstance(m["body"], dict) and m["body"].get("order_id") in ids and m["body"].get("status") == "pending"]
    assert seen == ids, f"expected {ids}, got {seen}"


def test_infra_order_status_message_becomes_a_backend_callback(operator, warehouse, factory, rabbit, vehicle_id):
    """A status message from Infra on the `vehicles` key (carrying order_id) drives POST /infra/orders/{id}/status: in_progress then completed"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(6)
    o = factory.order(operator, [(s.id, 2)])
    _publish_vehicle_msg(rabbit, {"order_id": o["id"], "status": "in_progress", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"})
    wait_until(lambda: factory.get_order(o["id"])["status"] == "in_progress", timeout=30, desc="order in_progress through the bridge")
    assert factory.get_order(o["id"])["assigned_vehicle_id"] == vid
    _publish_vehicle_msg(rabbit, {"order_id": o["id"], "status": "completed", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:01:00Z"})
    wait_until(lambda: factory.get_order(o["id"])["status"] == "completed", timeout=30, desc="order completed through the bridge")
    assert factory.stock(s.id)["physical"] == 4


def test_infra_telemetry_message_becomes_a_backend_callback(warehouse, rabbit, vehicle_id):
    """Telemetry (vehicle_id, no order_id) drives POST /infra/vehicles/{id}/telemetry; last_qr is not forwarded and does not break it"""
    infra_vid, vid = vehicle_id
    _publish_vehicle_msg(rabbit, {"vehicle_id": infra_vid, "status": "busy", "battery": 73, "last_qr": "WH2|PA1", "error": None, "timestamp": "2026-10-27T12:00:00Z"})
    v = wait_until(lambda: warehouse.get(f"/vehicles/{vid}").body if warehouse.get(f"/vehicles/{vid}").status == 200 else None, timeout=30, desc="vehicle created from telemetry")
    assert v["status"] == "busy" and v["battery"] == 73
    assert "last_qr" not in json.dumps(v)


def test_rejected_messages_are_discarded_not_retried(operator, warehouse, factory, rabbit, stack, vehicle_id):
    """A 4xx answer (unknown order -> 404, finished order -> 409) discards the message: the queue drains, later messages still flow"""
    infra_vid, _ = vehicle_id
    since = time.time() - 1
    s = factory.stocked(3)
    done = factory.order(operator, [(s.id, 1)])
    operator.post(f"/orders/{done['id']}/cancel").expect(200)
    _publish_vehicle_msg(rabbit, {"order_id": f"ghost-{uid()}", "status": "in_progress", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"})
    _publish_vehicle_msg(rabbit, {"order_id": done["id"], "status": "completed", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"})
    ok = factory.order(operator, [(s.id, 1)])
    _publish_vehicle_msg(rabbit, {"order_id": ok["id"], "status": "in_progress", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"})
    wait_until(lambda: factory.get_order(ok["id"])["status"] == "in_progress", timeout=30, desc="valid message behind two rejected ones")
    wait_until(lambda: rabbit.queue_depth("wh-backend.vehicles") == 0, timeout=20, desc="vehicles queue drained")
    logs = stack.logs("bridge", since=since)
    assert logs.count("backend rejected message") >= 2, "the 404 and the 409 must be logged as rejected"
    assert factory.get_order(done["id"])["status"] == "cancelled"


def test_garbage_messages_are_dropped_and_the_bridge_keeps_working(warehouse, rabbit, stack, vehicle_id):
    """Non-JSON and unclassifiable messages (neither order_id nor vehicle_id) are logged and dropped; the next telemetry still gets through"""
    infra_vid, vid = vehicle_id
    since = time.time() - 1
    rabbit.publish("vehicles", "this is not json")
    rabbit.publish("vehicles", {"hello": "world"})
    _publish_vehicle_msg(rabbit, {"vehicle_id": infra_vid, "status": "idle", "battery": 31, "last_qr": None, "error": None, "timestamp": "2026-10-27T12:00:00Z"})
    wait_until(lambda: warehouse.get(f"/vehicles/{vid}").status == 200 and warehouse.get(f"/vehicles/{vid}").json_("battery") == 31, timeout=30, desc="telemetry after garbage")
    wait_until(lambda: rabbit.queue_depth("wh-backend.vehicles") == 0, timeout=20, desc="queue drained")
    assert "dropping unclassifiable" in stack.logs("bridge", since=since)
    assert stack.status("bridge") == "running"


def test_bridge_forwards_the_api_key_and_request_ids(admin, operator, factory, rabbit, vehicle_id):
    """Callbacks carry X-Api-Key (otherwise they would be 401 and discarded) and the bridge logs correlate with backend logs by request_id"""
    infra_vid, _ = vehicle_id
    s = factory.stocked(3)
    o = factory.order(operator, [(s.id, 1)])
    _publish_vehicle_msg(rabbit, {"order_id": o["id"], "status": "in_progress", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"})
    wait_until(lambda: factory.get_order(o["id"])["status"] == "in_progress", timeout=30, desc="callback accepted (api key forwarded)")

    def correlated():
        lines = admin.post("/console/logs/query", json={"service": "bridge", "text": "delivered to backend", "limit": 50}).json_("lines")
        return [x for x in lines if x.get("request_id")] or None

    assert wait_until(correlated, timeout=60, interval=3, desc="bridge log lines with request ids")
