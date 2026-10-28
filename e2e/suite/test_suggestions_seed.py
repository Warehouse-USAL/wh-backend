"""Area: suggestions model preloaded from the demo seed (backend DemoOrderEventPublisher -> order.completed -> Redis).

The stack boots with SEED_DEMO=true, so MongoDB holds ~two years of orders; the backend announces every COMPLETED
one on order.completed and the suggestions service must end up with a model of exactly those orders. The ground
truth below is computed from GET /orders (MongoDB), never from the service."""
import math
from collections import Counter, defaultdict
from datetime import datetime, timezone
from itertools import combinations

import pytest

from lib import config
from lib.api import Api
from lib.infra import suggestions_progress, wait_suggestions_caught_up
from test_suggestions import _skus, _token

DECAY_FACTOR, DECAY_INTERVAL_S = 0.97, 15 * 60  # docker-compose defaults of the suggestions service


@pytest.fixture(scope="module")
def sg():
    return Api(config.SUGGESTIONS)


@pytest.fixture(scope="module")
def completed(admin, redis_c):
    """Every COMPLETED order in MongoDB, once the suggestions consumer has caught up with everything published."""
    wait_suggestions_caught_up()
    orders, page = [], 0
    while True:
        r = admin.get(f"/orders?status=completed&size=50&page={page}").expect(200)
        orders += r.json_("orders")
        page += 1
        if page >= r.json_("pagination", "total_pages"):
            return orders


def _lines(order):
    """sku -> quantity of one order (lines of the same SKU summed), as the service counts them."""
    q = Counter()
    for it in order["items"]:
        q[it["sku"]] += it["quantity"]
    return q


def _users(completed):
    by_user = defaultdict(Counter)
    for o in completed:
        by_user[o["requested_by_user_id"]].update(_lines(o))
    return by_user


def test_the_topic_holds_one_event_per_completed_order_and_the_consumer_applied_them_all(completed):
    """order.completed holds at least one event per COMPLETED order in MongoDB (the seeded ones plus those of earlier tests) and the group committed all of them"""
    done, end = suggestions_progress()
    assert len(completed) > 10_000, "the demo seed has about 25k completed orders"
    assert end >= len(completed) and done == end, (len(completed), done, end)


def test_order_counts_per_sku_equal_the_orders_in_mongo(completed, redis_c):
    """sku:count (orders containing each SKU) equals the number of completed orders containing it, for every SKU"""
    expected = Counter()
    for o in completed:
        expected.update(_lines(o).keys())
    got = {k: int(v) for k, v in redis_c.hgetall("sku:count").items()}
    assert got == dict(expected)


def test_user_histories_equal_what_mongo_says_they_ordered(completed, redis_c):
    """user:{id}:skus holds the quantities each user ordered, checked for the 5 busiest buyers"""
    by_user = _users(completed)
    busiest = sorted(by_user, key=lambda u: -sum(by_user[u].values()))[:5]
    for u in busiest:
        got = {k: int(v) for k, v in redis_c.zrange(f"user:{u}:skus", 0, -1, withscores=True)}
        assert got == dict(by_user[u]), u


def test_user_recommendations_are_non_empty_and_exclude_the_users_history(sg, completed):
    """/user/:id for seeded buyers answers recommendations, none of them a SKU the user already bought"""
    by_user = _users(completed)
    catalogue = set().union(*by_user.values())
    # Users who already bought (nearly) everything are legitimately told nothing new: pick buyers with room to recommend.
    open_ended = [u for u in by_user if len(by_user[u]) <= len(catalogue) - 3]
    assert len(open_ended) >= 3
    for u in sorted(open_ended, key=lambda u: -sum(by_user[u].values()))[:3]:
        recs = _skus(sg.get(f"/v1/recommendations/user/{u}", token=_token(u)).expect(200))
        assert recs, u
        assert not set(recs) & set(by_user[u]), (u, recs)


def _expected_cart(completed, cart_sku, limit=5):
    """Cosine co-purchase score cooccur(a,b)/sqrt(count(a)*count(b)) computed from the orders themselves."""
    count, co = Counter(), Counter()
    for o in completed:
        skus = sorted(_lines(o))
        count.update(skus)
        for a, b in combinations(skus, 2):
            if cart_sku in (a, b):
                co[b if a == cart_sku else a] += 1
    scored = {b: n / math.sqrt(count[cart_sku] * count[b]) for b, n in co.items()}
    return sorted(scored.items(), key=lambda kv: (-kv[1], kv[0]))[:limit], co


def test_cart_recommendations_match_the_copurchases_in_mongo(sg, completed):
    """For 3 seeded SKUs the service's 'also add' answer equals the popularity-normalised co-purchase ranking computed from MongoDB's orders"""
    popularity = Counter()
    for o in completed:
        popularity.update(_lines(o).keys())
    tok = _token("e2e-seed")
    for cart_sku in [s for s, _ in popularity.most_common(3)]:
        expected, raw = _expected_cart(completed, cart_sku)
        got = sg.get(f"/v1/recommendations/cart?skus={cart_sku}&limit=5", token=tok).expect(200).json_("recommendations")
        assert got and cart_sku not in _skus_of(got)
        assert [round(r["score"], 9) for r in got] == [round(s, 9) for _, s in expected], (cart_sku, got, expected)
        assert {r["sku"] for r in got} == {s for s, _ in expected}, (cart_sku, got, expected)


def _skus_of(recs):
    return [r["sku"] for r in recs]


def test_hot_favours_recently_ordered_skus(sg, completed):
    """/hot is the age-weighted demand: its top 10 equal quantity * 0.97^(age/15min) summed over MongoDB's orders (up to the decay rounds run since), far below the raw two-year totals"""
    now = datetime.now(timezone.utc)
    weighted, raw = Counter(), Counter()
    for o in completed:
        age = max((now - datetime.fromisoformat(o["timestamps"]["completed_at"].replace("Z", "+00:00"))).total_seconds(), 0)
        w = DECAY_FACTOR ** (age / DECAY_INTERVAL_S)
        for sku, q in _lines(o).items():
            raw[sku] += q
            if w >= 0.01:
                weighted[sku] += q * w
    got = sg.get("/v1/recommendations/hot?limit=10", token=_token("e2e-seed")).expect(200).json_("recommendations")
    # Only SKUs ordered within the last ~38 h are above the 0.01 floor, so the list may be shorter than the limit.
    assert len(got) == min(10, len(weighted)) and len(got) >= 3, (len(weighted), got)
    # The service decays in 15 min rounds and the test measures age continuously, so a uniform factor (and a
    # few percent between events) separates the two; the shape must match.
    scale = got[0]["score"] / weighted[got[0]["sku"]]
    assert 0.5 < scale < 1.5, scale
    for r in got:
        assert r["score"] == pytest.approx(weighted[r["sku"]] * scale, rel=0.1), (r, weighted[r["sku"]])
    top_weighted = {s for s, _ in weighted.most_common(10)}
    assert {r["sku"] for r in got} <= set(weighted) and len({r["sku"] for r in got} & top_weighted) >= min(8, len(got)), (_skus_of(got), sorted(top_weighted))
    assert all(r["score"] < 0.2 * raw[r["sku"]] for r in got), "hot must be a recency-weighted number, not the two-year total"
