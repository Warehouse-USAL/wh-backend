"""Area: resilience. Each test takes one dependency away, checks the behaviour, and ALWAYS restores the stack afterwards."""
import io
import time
from contextlib import contextmanager

import httpx
import pytest

from lib import config
from lib.api import Api, login, wait_until
from lib.factory import uid

pytestmark = pytest.mark.resilience


@contextmanager
def outage(stack, service, *, kill=False):
    """Stops (or kills) `service`; on exit restarts it and waits until it and the backend are healthy again."""
    stack.kill(service) if kill else stack.stop(service)
    try:
        yield
    finally:
        stack.start(service)
        stack.wait_healthy(service, 180)
        stack.wait_healthy("backend", 240)
        wait_until(lambda: httpx.get(f"{config.BACKEND}/actuator/health", timeout=5).status_code == 200, timeout=120, desc="backend health")


@pytest.fixture(scope="module", autouse=True)
def _manual_infra(stack):
    """These tests play Infra by hand: make sure the fake Infra (started by the flow group) is not racing them."""
    if stack.status("fakeinfra") == "running":
        stack.stop("fakeinfra")
    yield


@pytest.fixture
def op(operator):
    # tokens are stateless JWTs: they survive every restart below, so reuse the session operator
    return operator


def test_kafka_outage_orders_are_not_lost(stack, op, factory, kafka, rabbit):
    """Redpanda down: the API keeps accepting orders; once Kafka is back, every order created during the outage reaches Kafka and RabbitMQ"""
    s = factory.stocked(10)
    with outage(stack, "redpanda"):
        created = [op.post("/orders", json=factory.order_body([(s.id, 1)])) for _ in range(2)]
        assert all(r.status in (201, 503) for r in created), [r.describe() for r in created]
    ids = [r.json_("order", "id") for r in created if r.status == 201]
    assert ids, "no order accepted during the Kafka outage (acceptable: 503), nothing to verify"
    for oid in ids:
        kafka.wait_order(oid, "pending", timeout=120)
        rabbit.wait_order(oid, "pending", timeout=120)


def test_rabbitmq_outage_bridge_reconnects_and_nothing_is_lost(stack, op, factory, rabbit, warehouse):
    """RabbitMQ down: orders are still accepted; after it returns the bridge reconnects, delivers the backlog and Infra callbacks flow again"""
    s = factory.stocked(10)
    with outage(stack, "rabbitmq"):
        o = factory.order(op, [(s.id, 1)])
        assert warehouse.get("/products?size=1").status == 200
    rabbit.ensure_sink()
    rabbit.wait_order(o["id"], "pending", timeout=120)
    infra_vid = f"VHC-RMQ-{uid(6)}"
    assert wait_until(lambda: rabbit.publish("vehicles", {"order_id": o["id"], "status": "in_progress", "vehicle_id": infra_vid, "timestamp": "2026-10-27T12:00:00Z"}) or None, timeout=60, desc="vehicles queue back")
    wait_until(lambda: factory.get_order(o["id"])["status"] == "in_progress", timeout=90, desc="callback after reconnect")


def test_bridge_restart_resumes_from_the_committed_offset(stack, op, factory, rabbit):
    """Bridge down while orders are created: no loss and no duplicates once it is started again"""
    s = factory.stocked(10)
    with outage(stack, "bridge"):
        ids = [factory.order(op, [(s.id, 1)])["id"] for _ in range(3)]
        time.sleep(2)
    for oid in ids:
        rabbit.wait_order(oid, "pending", timeout=90)
    seen = [m["body"]["order_id"] for m in rabbit.seen if isinstance(m["body"], dict) and m["body"].get("order_id") in ids and m["body"].get("status") == "pending"]
    assert sorted(seen) == sorted(ids), f"duplicates or losses: {seen}"


def test_mongodb_outage_fails_fast_and_cleanly(stack, op, anon):
    """MongoDB down: requests answer 503 {error} within 10s and health reports DOWN quickly"""
    with outage(stack, "mongodb"):
        t0 = time.monotonic()
        r = op.get("/products?size=1")
        took = time.monotonic() - t0
        assert r.status == 503 and "error" in r.body and took < 10, (r.status, took, r.body)
        h = httpx.get(f"{config.BACKEND}/actuator/health", timeout=5)
        assert h.status_code == 503


def test_mongodb_outage_recovers_without_restarting_the_backend(stack, op, factory, warehouse):
    """After MongoDB returns the backend serves again by itself, with all earlier data intact"""
    s = factory.stocked(5)
    before = warehouse.get("/products?size=1").json_("pagination", "total_elements")
    with outage(stack, "mongodb"):
        pass
    wait_until(lambda: warehouse.get("/products?size=1").status == 200, timeout=120, desc="API serving after Mongo restart")
    assert warehouse.get("/products?size=1").json_("pagination", "total_elements") == before
    assert warehouse.get(f"/products/{s.id}").json_("product", "id") == s.id
    factory.order(op, [(s.id, 1)])


def test_minio_outage_only_affects_files(stack, op, warehouse, factory):
    """MinIO down: catalogue, orders and health are unaffected; file upload fails with an error instead of hanging; uploads work again afterwards"""
    png = b"\x89PNG\r\n\x1a\n" + b"e2e" * 20
    s = factory.stocked(5)
    with outage(stack, "minio"):
        t0 = time.monotonic()
        assert warehouse.get("/products?size=1").status == 200
        factory.order(op, [(s.id, 1)])
        try:
            r = warehouse.post("/api/v1/files/upload", files={"file": ("a.png", io.BytesIO(png), "image/png")}, data={"path": "e2e"})
        except httpx.TimeoutException:
            pytest.fail("file upload hung for >30s while MinIO is down instead of failing fast")
        assert r.status >= 400 and time.monotonic() - t0 < 60, r.describe()
    def again():
        try:
            r = warehouse.post("/api/v1/files/upload", files={"file": ("a.png", io.BytesIO(png), "image/png")}, data={"path": "e2e"})
        except httpx.TimeoutException:
            return None
        return r if r.status in (200, 201) else None

    assert wait_until(again, timeout=180, interval=3, desc="upload works again after MinIO is back")


def test_suggestions_redis_outage_does_not_touch_the_backend_and_the_consumer_catches_up(stack, admin, factory, infra, redis_c):
    """Suggestions' Redis down: backend unaffected, the service reports unhealthy, and the order completed meanwhile is applied after Redis returns (offset not committed before applying)"""
    from test_suggestions import _buyer, _token  # reuse helpers
    s = factory.stocked(10, sku=f"RSL-{uid(6).upper()}")
    sg = Api(config.SUGGESTIONS)
    with outage(stack, "suggestions-redis"):
        assert admin.get("/products?size=1").status == 200
        r = httpx.get(f"{config.SUGGESTIONS}/healthz", timeout=10)
        assert r.status_code != 200, "healthz must flag the dead Redis"
        u, _, _ = _buyer(admin, factory, infra, [s])
        assert sg.get("/v1/recommendations/hot", token=_token(u.id)).status == 500
    stack.wait_healthy("suggestions", 120)
    from lib.infra import redis_client
    rc = redis_client()
    wait_until(lambda: rc.zscore(f"user:{u.id}:skus", s.product["sku"]), timeout=90, desc="consumer applied the event after Redis came back")
    assert sg.get("/v1/recommendations/hot", token=_token(u.id)).status == 200


def test_backend_crash_mid_order_recovers_and_the_order_can_finish(stack, op, factory, infra, warehouse, admin):
    """kill -9 the backend with an order in progress: it restarts by itself state intact (no seed duplication, same JWTs valid) and Infra can still complete the order"""
    s = factory.stocked(10)
    o = factory.order(op, [(s.id, 4)])
    vid = f"VHC-CRASH-{uid(6)}"
    infra.status(o["id"], "in_progress", vid).expect(200)
    products_before = warehouse.get("/products?size=1").json_("pagination", "total_elements")
    users_before = admin.get("/users?size=1").json_("pagination", "total_elements")
    with outage(stack, "backend", kill=True):
        with pytest.raises(Exception):
            httpx.get(f"{config.BACKEND}/actuator/health", timeout=3)
    assert factory.get_order(o["id"])["status"] == "in_progress"
    assert warehouse.get("/products?size=1").json_("pagination", "total_elements") == products_before, "seed ran again?"
    assert admin.get("/users?size=1").json_("pagination", "total_elements") == users_before
    infra.status(o["id"], "completed", vid).expect(200)
    assert factory.get_order(o["id"])["status"] == "completed" and factory.stock(s.id)["physical"] == 6


def test_graceful_backend_restart_keeps_websocket_clients_able_to_reconnect(stack, admin, operator, factory):
    """After a backend restart a new websocket client gets events again (sessions are per process, clients must reconnect)"""
    from lib.ws import feed
    s = factory.stocked(5)
    with outage(stack, "backend"):
        pass
    with feed("/ws/v1/orders", admin.token) as f:
        o = factory.order(operator, [(s.id, 1)])
        f.wait(lambda e: e.get("payload", {}).get("id") == o["id"])
