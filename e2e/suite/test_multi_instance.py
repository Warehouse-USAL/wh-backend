"""Area: two backend instances on the same MongoDB / Redpanda / MinIO (run with `make e2e-multi`, never in the default suite).

`backend` and `backend2` booted at the same moment on an empty database. Every test drives BOTH instances,
alternating requests, to prove that nothing relies on being the only process.
"""
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest

from lib import config
from lib.api import Api, login, wait_until
from lib.factory import Factory, uid
from lib.ws import feed

pytestmark = pytest.mark.multi


@pytest.fixture(scope="module")
def b2_url():
    assert config.BACKEND2, "BACKEND2_URL is not set: run these tests through e2e/multi.sh (make e2e-multi)"
    return config.BACKEND2


def pair(email, password, b2_url):
    """The same user logged in on each instance."""
    return [login(email, password), login(email, password, base=b2_url)]


@pytest.fixture(scope="module")
def operators(b2_url):
    return pair(*config.ACCOUNTS["OPERATOR"], b2_url)


@pytest.fixture(scope="module")
def operators2(b2_url):
    return pair("operator2@smartwarehouse.local", config.DEMO_PASSWORD, b2_url)


@pytest.fixture(scope="module")
def warehouses(b2_url):
    return pair(*config.ACCOUNTS["ADMIN_WAREHOUSE"], b2_url)


@pytest.fixture(scope="module")
def admins(b2_url):
    return pair(*config.ACCOUNTS["SUPERADMIN"], b2_url)


def _spread(apis, n):
    return [apis[i % len(apis)] for i in range(n)]


# ───────────────────────────── boot ─────────────────────────────
def test_both_instances_booted_together_are_healthy_and_see_one_consistent_dataset(warehouses, admins):
    """Two instances seeding/initialising the same empty database at once: both healthy, same data, no duplicated admin or warehouse layout"""
    for api in admins:
        assert Api(api.base).get("/actuator/health", token=None).status == 200
    counts = []
    for wh in warehouses:
        zones = wh.get("/warehouse/zones?size=100").expect(200).body
        lines = wh.get("/warehouse/lines?size=200").body if wh.get("/warehouse/lines?size=200").status == 200 else None
        prods = wh.get("/products?size=1").expect(200).body
        counts.append((len(zones.get("zones", zones)) if isinstance(zones, dict) else len(zones),
                       lines and len(lines.get("lines", lines)),
                       prods["pagination"]["total_elements"]))
    assert counts[0] == counts[1], counts
    users = admins[0].get("/users?size=200").expect(200).body
    rows = users.get("users", users)
    emails = [u["email"] for u in rows]
    assert len(emails) == len(set(emails)), "duplicated users after a concurrent boot"
    assert emails.count(config.ADMIN_EMAIL) == 1


def test_a_write_on_one_instance_is_read_on_the_other(warehouses, operators):
    """Read-your-write across instances (shared MongoDB): create a product on #1, read it on #2"""
    f = Factory(warehouses[0], warehouses[0])
    p = f.product()
    assert warehouses[1].get(f"/products/{p['id']}").expect(200).json_("product", "sku") == p["sku"]


# ───────────────────────────── stock ─────────────────────────────
def test_concurrent_orders_split_across_instances_never_oversell(warehouses, operators, operators2):
    """12 parallel orders of 3 units on 10 units, alternating instances and users: exactly 3 win, the rest INSUFFICIENT_STOCK, reserved never above physical"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(10)
    body = f.order_body([(s.id, 3)])
    apis = [operators[0], operators[1], operators2[0], operators2[1]]
    with ThreadPoolExecutor(max_workers=12) as pool:
        rs = list(pool.map(lambda a: a.post("/orders", json=body), _spread(apis, 12)))
    won = [r for r in rs if r.status == 201]
    lost = [r for r in rs if r.status != 201]
    assert len(won) == 3, [(r.status, r.text[:80]) for r in rs]
    assert all(r.status == 400 and r.code == "INSUFFICIENT_STOCK" for r in lost), [(r.status, r.text[:120]) for r in lost]
    for wh in warehouses:  # both instances report the same numbers
        st = wh.get(f"/products/{s.id}").expect(200).json_("product", "stock")
        assert st["reserved"] == 9 and st["available"] == 1 and st["physical"] == 10


def test_oversell_does_not_happen_over_many_rounds(warehouses, operators):
    """Five products with 4 units, 10 parallel single-unit orders each across both instances: never more than 4 reserved"""
    f = Factory(warehouses[0], warehouses[0])
    for _ in range(5):
        s = f.stocked(4)
        body = f.order_body([(s.id, 1)])
        with ThreadPoolExecutor(max_workers=10) as pool:
            rs = list(pool.map(lambda a: a.post("/orders", json=body), _spread(operators, 10)))
        assert sum(r.status == 201 for r in rs) == 4, [(r.status, r.text[:80]) for r in rs]
        assert not [r for r in rs if r.status >= 500], [(r.status, r.text[:120]) for r in rs]
        assert f.stock(s.id)["reserved"] == 4


def test_the_same_idempotency_key_on_both_instances_yields_one_order(warehouses, operators):
    """8 racing requests with one Idempotency-Key, half to each instance: one order, everyone gets it, one reservation"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(10)
    key = str(uuid.uuid4())
    body = f.order_body([(s.id, 2)])
    with ThreadPoolExecutor(max_workers=8) as pool:
        rs = list(pool.map(lambda a: a.post("/orders", json=body, headers={"Idempotency-Key": key}), _spread(operators, 8)))
    assert all(r.status == 201 for r in rs), [(r.status, r.text[:100]) for r in rs]
    assert len({r.json_("order", "id") for r in rs}) == 1
    assert f.stock(s.id)["reserved"] == 2
    again = operators[1].post("/orders", json=body, headers={"Idempotency-Key": key}).expect(201)
    assert again.json_("order", "id") == rs[0].json_("order", "id")


def test_concurrent_adjustments_across_instances_have_one_winner_and_a_clean_conflict(warehouses):
    """Two 60-unit adjustments of a 100-unit position, one per instance, at once: exactly one wins, the other gets 400/409, nobody 5xx, stock is 40"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(100)
    pid = s.position["id_position"]
    with ThreadPoolExecutor(max_workers=2) as pool:
        rs = list(pool.map(lambda a: a.post(f"/warehouse/positions/{pid}/stock-adjustments", json={"quantity": 60}), warehouses))
    assert not [r for r in rs if r.status >= 500], [(r.status, r.text[:160]) for r in rs]
    assert sum(r.status == 201 for r in rs) == 1, [(r.status, r.text[:160]) for r in rs]
    assert all(r.status in (400, 409) for r in rs if r.status != 201), [(r.status, r.text[:160]) for r in rs]
    assert warehouses[1].get(f"/warehouse/positions/{pid}").json_("current_stock") == 40


def test_many_parallel_adjustments_across_instances_never_500(warehouses):
    """16 parallel 1-unit adjustments of one position alternating instances: only 201 or STOCK_CHANGE_CONFLICT, the stock equals the winners' sum"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(100)
    pid = s.position["id_position"]
    with ThreadPoolExecutor(max_workers=16) as pool:
        rs = list(pool.map(lambda a: a.post(f"/warehouse/positions/{pid}/stock-adjustments", json={"quantity": 1}), _spread(warehouses, 16)))
    assert not [r for r in rs if r.status not in (201, 409)], [(r.status, r.text[:160]) for r in rs]
    assert all(r.code == "STOCK_CHANGE_CONFLICT" for r in rs if r.status == 409)
    won = sum(r.status == 201 for r in rs)
    assert won >= 1
    assert warehouses[1].get(f"/warehouse/positions/{pid}").json_("current_stock") == 100 - won


# ───────────────────────────── auth ─────────────────────────────
def test_a_token_issued_by_one_instance_works_on_the_other(operators):
    """Stateless JWT with a shared secret: a token from #1 is accepted by #2"""
    foreign = Api(operators[1].base, operators[0].token)
    foreign.get("/orders?size=1").expect(200)


def test_jwt_deactivation_reaches_the_other_instance_within_the_cache_ttl(admins, b2_url):
    """Deactivating a user on #1 is felt at once on #1 and on #2 within jwt.user-status-cache-ttl-ms (15 s by default); the delay is measured"""
    f = Factory(admins[0], admins[0])
    u = f.user(admins[0], "OPERATOR")
    on1, on2 = pair(u.email, u.password, b2_url)
    on2.get("/orders?size=1").expect(200)  # #2 now caches "active"
    on1.get("/orders?size=1").expect(200)
    admins[0].patch(f"/users/{u.id}", json={"active": False}).expect(200)
    assert on1.get("/orders?size=1").status in (401, 403), "the instance that deactivated must react immediately"
    t0 = time.monotonic()
    wait_until(lambda: on2.get("/orders?size=1").status in (401, 403), timeout=45, interval=0.5, desc="instance #2 to reject the deactivated user")
    delay = time.monotonic() - t0
    print(f"\nMEASURED: deactivation visible on the other instance after {delay:.1f}s")
    assert delay <= 20, f"{delay:.1f}s is above the default 15 s TTL plus margin"


# ───────────────────────────── websockets ─────────────────────────────
@pytest.mark.known_failure(bug="BUG-MULTI-1", reason="WebSocket sessions live in each instance's memory and events are broadcast only to the sessions of the instance that handled the request")
def test_websocket_events_published_by_one_instance_reach_clients_of_the_other(admins, operators, warehouses, b2_url):
    """A client connected to #2 receives order.updated for an order created through #1"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(5)
    with feed("/ws/v1/orders", admins[1].token, base=b2_url) as ws2:
        ws2.pump(0.5)
        o = f.order(operators[0], [(s.id, 1)])
        ev = ws2.wait(lambda e: e.get("event") == "order.updated" and e.get("payload", {}).get("id") == o["id"], timeout=10)
        assert ev


def test_websocket_events_reach_clients_of_the_same_instance(admins, operators, warehouses, b2_url):
    """Control for the test above: a client on the instance that handles the request does get the event (both instances)"""
    f = Factory(warehouses[0], warehouses[0])
    s = f.stocked(5)
    for i, base in enumerate((config.BACKEND, b2_url)):
        with feed("/ws/v1/orders", admins[i].token, base=base) as ws:
            ws.pump(0.5)
            o = f.order(operators[i], [(s.id, 1)])
            ws.wait(lambda e, oid=o["id"]: e.get("event") == "order.updated" and e.get("payload", {}).get("id") == oid, timeout=10)


# ───────────────────────────── jobs ─────────────────────────────
def test_restock_apply_run_concurrently_on_both_instances_is_idempotent(warehouses):
    """No @Scheduled job exists in the backend; the only periodic one is the external restock-cron (a single container calling apply). If two runs overlap, one per instance, both succeed and agree"""
    params = {"params": {"alpha": 0.4, "recent_days": 30, "long_days": 180, "safety_days": 7, "lead_time_days": 7, "coverage_days": 30}}
    with ThreadPoolExecutor(max_workers=2) as pool:
        rs = list(pool.map(lambda a: a.post("/metrics/restock-suggestions/apply", json=params), warehouses))
    assert [r.status for r in rs] == [200, 200], [(r.status, r.text[:200]) for r in rs]
    key = lambda r: sorted((x.get("product_id"), x.get("suggested_quantity")) for x in r.json_("data"))  # noqa: E731
    assert key(rs[0]) == key(rs[1])
