"""Area: suggestions service (order.completed consumer -> Redis model -> /v1/recommendations API)."""
import time

import httpx
import jwt
import pytest

from lib import config
from lib.api import Api, login, wait_until
from lib.factory import uid


@pytest.fixture(scope="module")
def sg():
    return Api(config.SUGGESTIONS)


def _token(sub, role="OPERATOR", secret=None, alg="HS512", ttl=600):
    now = int(time.time())
    return jwt.encode({"sub": sub, "email": "x@x.x", "role": role, "iat": now, "exp": now + ttl}, secret or config.JWT_SECRET, algorithm=alg)


def _buyer(admin, factory, infra, products, qty=1):
    """A fresh OPERATOR who buys and receives (completes) one order with the given products."""
    u = factory.user(admin, "OPERATOR")
    api = login(u.email, u.password)
    vid = f"VHC-SG-{uid(6)}"
    o = factory.order(api, [(p.id if hasattr(p, "id") else p["id"], qty) for p in products])
    infra.status(o["id"], "in_progress", vid).expect(200)
    infra.status(o["id"], "completed", vid).expect(200)
    return u, api, o


def _skus(resp):
    return [r["sku"] for r in resp.json_("recommendations")]


def test_healthz_needs_no_auth(sg):
    """GET /healthz is public and healthy (Redis and consumer alive)"""
    sg.get("/healthz", token=None).expect(200)


def test_recommendations_require_a_valid_jwt(sg, admin):
    """No token, malformed, wrong secret, expired and unsigned tokens -> 401 unauthorized in the service's own error format"""
    for path in ("/v1/recommendations/hot", "/v1/recommendations/cart?skus=A", f"/v1/recommendations/user/{admin.id}"):
        for tok in (None, "garbage", _token(admin.id, secret="x" * 64), _token(admin.id, ttl=-60)):
            r = sg.get(path, token=tok)
            assert r.status == 401 and r.body["error"] == "unauthorized", (path, tok, r.describe())
    assert sg.get("/v1/recommendations/hot", token=_token(admin.id, alg="HS256")).status == 200, "HS256 accepted too"


def test_completed_orders_feed_the_redis_model(admin, factory, infra, redis_c):
    """A completed order reaches suggestions through Kafka `order.completed` and is recorded: user history, buyers and co-occurrence"""
    a, b = factory.stocked(10, sku=f"SGA-{uid(6).upper()}"), factory.stocked(10, sku=f"SGB-{uid(6).upper()}")
    u, _, o = _buyer(admin, factory, infra, [a, b], qty=2)
    sa, sb = a.product["sku"], b.product["sku"]
    wait_until(lambda: redis_c.zscore(f"user:{u.id}:skus", sa), timeout=30, desc="user history in Redis")
    assert redis_c.zscore(f"user:{u.id}:skus", sa) == 2 and redis_c.zscore(f"user:{u.id}:skus", sb) == 2
    assert redis_c.zscore(f"sku:{sa}:buyers", u.id)
    assert redis_c.zscore(f"cooccur:{sa}", sb) and redis_c.zscore(f"cooccur:{sb}", sa)
    assert redis_c.zscore("hot:global", sa) and redis_c.zscore("hot:global", sb)


def test_cancelled_orders_do_not_feed_the_model(admin, operator, factory, redis_c, kafka):
    """Only completed orders count: a cancelled order produces no recommendation data"""
    s = factory.stocked(5, sku=f"SGC-{uid(6).upper()}")
    o = factory.order(operator, [(s.id, 1)])
    operator.post(f"/orders/{o['id']}/cancel").expect(200)
    time.sleep(3)
    assert redis_c.zscore("hot:global", s.product["sku"]) is None and not redis_c.exists(f"cooccur:{s.product['sku']}")


def test_cart_recommendations_use_cooccurrence_and_get_equals_post(sg, admin, factory, infra, operator):
    """Customers who bought A and B together: a cart with A gets B, never A itself; GET (?skus=) and POST ({skus}) agree; limit honoured"""
    a, b, c = (factory.stocked(10, sku=f"SGX{i}-{uid(5).upper()}") for i in range(3))
    _buyer(admin, factory, infra, [a, b])
    _buyer(admin, factory, infra, [a, b])
    _buyer(admin, factory, infra, [a, c])
    sa, sb, sc = (x.product["sku"] for x in (a, b, c))
    tok = _token(operator.id)

    def ready():
        r = sg.get(f"/v1/recommendations/cart?skus={sa}", token=tok)
        return r if _skus(r) == [sb, sc] else None

    g = wait_until(ready, timeout=30, desc="co-occurrence recommendations")
    assert _skus(g) == [sb, sc] and g.json_("recommendations")[0]["score"] > g.json_("recommendations")[1]["score"]
    p = sg.post("/v1/recommendations/cart", token=tok, json={"skus": [sa]})
    assert p.body == g.body
    assert _skus(sg.get(f"/v1/recommendations/cart?skus={sa}&limit=1", token=tok)) == [sb]
    assert _skus(sg.get(f"/v1/recommendations/cart?skus={sa},{sb}", token=tok)) == [sc], "cart SKUs are excluded"
    assert sg.get("/v1/recommendations/cart?skus=NEVER-SEEN-" + uid(), token=tok).status == 200


def test_cart_validation(sg, operator):
    """Empty cart, >100 SKUs, over-long SKU, bad limit and bad JSON -> 400 bad_request"""
    tok = _token(operator.id)
    for r in (sg.get("/v1/recommendations/cart", token=tok), sg.get("/v1/recommendations/cart?skus=A&limit=0", token=tok),
              sg.get("/v1/recommendations/cart?skus=A&limit=51", token=tok), sg.get("/v1/recommendations/cart?skus=A&limit=x", token=tok),
              sg.get("/v1/recommendations/cart?skus=" + "S" * 65, token=tok),
              sg.post("/v1/recommendations/cart", token=tok, json={"skus": [f"S{i}" for i in range(101)]}),
              sg.post("/v1/recommendations/cart", token=tok, content="{nope", headers={"Content-Type": "application/json"}),
              sg.post("/v1/recommendations/cart", token=tok, json={"skus": ["A"], "limit": 99})):
        assert r.status == 400 and r.body["error"] == "bad_request", r.describe()
    assert sg.post("/v1/recommendations/cart", token=tok, json={"skus": ["A"], "limit": 50}).status == 200


def test_user_recommendations_collaborative_filtering_excludes_own_history(sg, admin, factory, infra):
    """Similar buyers' items are recommended; what the user already bought is not; a SUPERADMIN may read any user's, another OPERATOR may not"""
    a, b, c = (factory.stocked(10, sku=f"SGU{i}-{uid(5).upper()}") for i in range(3))
    me, me_api, _ = _buyer(admin, factory, infra, [a])
    _buyer(admin, factory, infra, [a, b])
    _buyer(admin, factory, infra, [a, b])
    sb = b.product["sku"]
    mine = _token(me.id)

    def ready():
        r = sg.get(f"/v1/recommendations/user/{me.id}", token=mine)
        return r if sb in _skus(r) else None

    r = wait_until(ready, timeout=30, desc="collaborative recommendation")
    assert a.product["sku"] not in _skus(r), "already bought"
    assert sg.get(f"/v1/recommendations/user/{me.id}", token=admin.token).status == 200
    other = _token(uid(12))
    sg.get(f"/v1/recommendations/user/{me.id}", token=other).expect(403)
    assert sg.get(f"/v1/recommendations/user/{me.id}", token=other).body["error"] == "forbidden"
    sg.get(f"/v1/recommendations/user/{me.id}?limit=0", token=mine).expect(400)


def test_cold_start_user_gets_the_hot_list(sg, admin, factory, infra):
    """A user without history receives the global popular list instead of nothing"""
    _buyer(admin, factory, infra, [factory.stocked(10, sku=f"SGH-{uid(6).upper()}")])
    newbie = factory.user(admin, "OPERATOR")
    r = sg.get(f"/v1/recommendations/user/{newbie.id}", token=_token(newbie.id)).expect(200)
    assert len(r.json_("recommendations")) >= 1


def test_hot_ranks_by_popularity_and_honours_limit(sg, admin, factory, infra, operator):
    """/hot returns SKUs by descending score; limit default 10, max 50"""
    top = factory.stocked(30, sku=f"SGT-{uid(6).upper()}")
    for _ in range(3):
        _buyer(admin, factory, infra, [top], qty=5)
    tok = _token(operator.id)
    r = wait_until(lambda: (lambda x: x if top.product["sku"] in _skus(x)[:3] else None)(sg.get("/v1/recommendations/hot", token=tok)), timeout=30, desc="hot ranking")
    scores = [x["score"] for x in r.json_("recommendations")]
    assert scores == sorted(scores, reverse=True) and len(scores) <= 10
    assert len(sg.get("/v1/recommendations/hot?limit=1", token=tok).json_("recommendations")) == 1
    sg.get("/v1/recommendations/hot?limit=51", token=tok).expect(400)


def test_unknown_routes_return_json_not_found(sg, operator):
    """Unknown paths answer 404"""
    assert sg.get("/v1/recommendations/nothing", token=_token(operator.id)).status == 404


@pytest.mark.known_failure(bug="BUG-SUGGEST-1", reason="Kafka redelivery of an order.completed event is counted twice (documented in suggestions/internal/ingest/apply.go: 'a replayed order counts twice'); no per-order dedupe key")
def test_replayed_event_is_ignored(admin, factory, infra, redis_c, stack):
    """Kafka redelivery (at-least-once) must not double count an order: re-publishing the same order.completed leaves scores unchanged"""
    from confluent_kafka import Producer
    from lib import config as c
    s = factory.stocked(10, sku=f"SGR-{uid(6).upper()}")
    u, _, o = _buyer(admin, factory, infra, [s], qty=2)
    sku = s.product["sku"]
    wait_until(lambda: redis_c.zscore(f"user:{u.id}:skus", sku), timeout=30, desc="first delivery")
    before = (redis_c.zscore(f"user:{u.id}:skus", sku), redis_c.zscore("hot:global", sku))
    import json
    p = Producer({"bootstrap.servers": c.KAFKA})
    p.produce("order.completed", key=o["id"], value=json.dumps({"orderId": o["id"], "userId": u.id, "destinationArea": "E2E-AREA",
              "items": [{"sku": sku, "productId": s.id, "quantity": 2}], "completedAt": "2026-10-27T12:00:00Z"}))
    p.flush(10)
    time.sleep(4)
    after = (redis_c.zscore(f"user:{u.id}:skus", sku), redis_c.zscore("hot:global", sku))
    assert after == before, f"replayed order counted twice: {before} -> {after}"
