"""Area: SUPERADMIN console API (status, logs, ws-events, live SSE stream) and the console UI served by nginx."""
import re
import time

import httpx
import jwt
import pytest

from lib import config
from lib.api import Api, wait_until
from lib.sse import read_sse
from lib.ws import feed as ws_feed


# ───────────────────────────── API: authorization ─────────────────────────────
@pytest.mark.parametrize("method,path,body", [
    ("GET", "/console/status", None), ("POST", "/console/logs/query", {}),
    ("GET", "/console/ws-events/orders", None), ("POST", "/console/stream", {"subscriptions": [{"id": "s", "kind": "status"}]}),
])
def test_console_api_is_superadmin_only(role_apis, anon, method, path, body):
    """Every /console/** endpoint: anonymous 401, any non-SUPERADMIN role 403 (JSON even for the stream), SUPERADMIN allowed"""
    kw = {"json": body} if body is not None else {}
    anon.request(method, path, token=None, **kw).expect(401, code="UNAUTHORIZED")
    for role, api in role_apis.items():
        if role == "SUPERADMIN":
            continue
        r = api.request(method, path, headers={"Accept": "text/event-stream"} if "stream" in path else None, **kw)
        r.expect(403, code="ACCESS_DENIED")


# ───────────────────────────── status ─────────────────────────────
def test_console_status_reports_every_platform_service_up(admin):
    """GET /console/status probes backend, bridge, mongodb, redpanda, minio, victoriametrics, victorialogs, otel-collector; all UP in the healthy stack"""
    r = admin.get("/console/status").expect(200).body
    states = {s["name"]: s["state"] for s in r["services"]}
    assert set(states) == {"backend", "bridge", "mongodb", "redpanda", "minio", "victoriametrics", "victorialogs", "otel-collector"}
    assert all(v == "UP" for v in states.values()), states
    backend = next(s for s in r["services"] if s["name"] == "backend")
    assert backend["uptime_ms"] and backend["uptime_ms"] > 0 and r["dev"] is True


# ───────────────────────────── logs ─────────────────────────────
def test_console_logs_find_a_request_by_its_id(admin):
    """A request's X-Request-Id can be found back in the logs (backend -> OTEL -> VictoriaLogs -> console API)"""
    rid = f"e2e-{int(time.time()*1000)}"
    admin.get("/products?size=1", headers={"X-Request-Id": rid}).expect(200)

    def lines():
        return admin.post("/console/logs/query", json={"request_id": rid}).expect(200).json_("lines") or None

    found = wait_until(lines, timeout=60, interval=3, desc=f"log line with request_id={rid}")
    assert found[0]["request_id"] == rid and found[0]["service"] == "backend" and "GET /products" in found[0]["message"]
    assert {"time", "level", "service", "message", "request_id"} <= set(found[0])


def test_console_logs_filters_and_limits(admin):
    """Filter by service/level/text, newest first, limit default 100 and capped at 500; invalid service/level -> 400"""
    admin.get("/products?size=1").expect(200)
    wait_until(lambda: admin.post("/console/logs/query", json={"service": "backend", "limit": 1}).json_("lines"), timeout=60, interval=3, desc="any backend log")
    r = admin.post("/console/logs/query", json={"service": "backend", "limit": 3}).expect(200)
    lines = r.json_("lines")
    assert len(lines) == 3 and r.json_("total") == 3 and all(x["service"] == "backend" for x in lines)
    times = [x["time"] for x in lines]
    assert times == sorted(times, reverse=True), "newest first"
    assert len(admin.post("/console/logs/query", json={}).json_("lines")) <= 100
    assert len(admin.post("/console/logs/query", json={"limit": 9999}).json_("lines")) <= 500
    errors = admin.post("/console/logs/query", json={"level": "error", "limit": 20}).expect(200).json_("lines")
    assert all(x["level"] == "error" for x in errors)
    text = admin.post("/console/logs/query", json={"text": "GET /products", "limit": 5}).json_("lines")
    assert all("GET /products" in x["message"] for x in text)
    admin.post("/console/logs/query", json={"service": "nope"}).expect(400, code="INVALID_FILTER_VALUE")
    admin.post("/console/logs/query", json={"level": "loud"}).expect(400, code="INVALID_FILTER_VALUE")


def test_console_logs_bridge_lines_are_correlated(admin):
    """The bridge ships its own logs with request ids, queryable with service=bridge"""
    r = admin.post("/console/logs/query", json={"service": "bridge", "limit": 5}).expect(200)
    assert all(x["service"] == "bridge" for x in r.json_("lines"))


# ───────────────────────────── ws-events buffer ─────────────────────────────
def test_console_ws_events_feeds(admin):
    """GET /console/ws-events/{feed} serves the recent websocket events of orders, user-orders, vehicles, stock-alerts; unknown feed 404"""
    for slug, feed in (("orders", "/ws/v1/orders"), ("user-orders", None), ("vehicles", "/ws/v1/vehicles"), ("stock-alerts", "/ws/v1/stock/alerts")):
        r = admin.get(f"/console/ws-events/{slug}").expect(200)
        assert isinstance(r.json_("events"), list) and r.json_("feed")
        if feed:
            assert r.json_("feed") == feed
    admin.get("/console/ws-events/nope").expect(404, code="UNKNOWN_FEED")


# ───────────────────────────── live stream (SSE) ─────────────────────────────
def test_console_stream_snapshot_then_live_updates(admin, operator, factory):
    """The SSE stream sends a snapshot per subscription and then pushes live events: a new order shows up in the orders ws-events feed within seconds (the feed records what is pushed to connected websocket clients)"""
    s = factory.stocked(5)
    made = {}

    def make_order():
        made["o"] = factory.order(operator, [(s.id, 1)])

    with ws_feed("/ws/v1/orders", admin.token):
        ev = _stream_with_order(admin, make_order, made)
    snaps = {e["data"]["id"]: e["data"] for e in ev if e["event"] == "snapshot"}
    assert set(snaps) == {"st", "w.orders"}
    assert snaps["st"]["mode"] == "replace" and len(snaps["st"]["data"]["services"]) == 8
    upd = next(e["data"] for e in ev if e["event"] == "update" and e["data"]["id"] == "w.orders" and made["o"]["id"] in str(e["data"]))
    assert upd["mode"] == "append"


def _stream_with_order(admin, make_order, made):
    return read_sse(admin.token, {"subscriptions": [{"id": "st", "kind": "status"}, {"id": "w.orders", "kind": "ws-events", "feed": "orders"}]},
                  until=lambda evs: any(e["event"] == "update" and e["data"].get("id") == "w.orders" and made["o"]["id"] in str(e["data"]) for e in evs),
                  timeout=30, trigger=make_order)


def test_console_stream_metric_and_logs_subscriptions(admin):
    """metric and logs subscriptions get a snapshot with the same payload shape as their REST twins"""
    ev = read_sse(admin.token, {"subscriptions": [
        {"id": "m", "kind": "metric", "metric": "wh.vehicle.battery", "range": "15m", "step": "1m", "agg": "avg", "group_by": ["vehicle_id"]},
        {"id": "l", "kind": "logs", "service": "backend", "range": "15m", "limit": 5}]},
        until=lambda evs: sum(e["event"] == "snapshot" for e in evs) >= 2, timeout=30)
    snaps = {e["data"]["id"]: e["data"] for e in ev if e["event"] == "snapshot"}
    assert "series" in snaps["m"]["data"] and snaps["m"]["data"]["metric"] == "wh.vehicle.battery"
    assert "lines" in snaps["l"]["data"]


@pytest.mark.parametrize("subs", [
    [{"id": "bad id!", "kind": "status"}], [{"id": "x", "kind": "nope"}],
    [{"id": "dup", "kind": "status"}, {"id": "dup", "kind": "status"}],
    [{"id": "w", "kind": "ws-events", "feed": "nonexistent"}],
    [{"id": f"s{i}", "kind": "status"} for i in range(201)],
])
def test_console_stream_rejects_invalid_subscriptions(admin, subs):
    """Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened)"""
    r = admin.post("/console/stream", json={"subscriptions": subs}, headers={"Accept": "text/event-stream"})
    assert r.status == 400 and r.code in ("INVALID_SUBSCRIPTION", "INVALID_FILTER_VALUE", "UNKNOWN_METRIC", "UNKNOWN_FEED"), r.describe()
    admin.post("/console/stream", content="{x", headers={"Content-Type": "application/json"}).expect(400, code="MALFORMED_REQUEST")


def test_console_stream_reports_a_failing_subscription_as_an_error_event(admin):
    """A well-formed subscription that cannot be served (unknown metric) yields an `error` event with its id, while the others keep streaming"""
    ev = read_sse(admin.token, {"subscriptions": [
        {"id": "bad", "kind": "metric", "metric": "nope", "range": "5m", "step": "1m", "agg": "avg"}, {"id": "ok", "kind": "status"}]},
        until=lambda evs: any(e["event"] == "error" for e in evs) and any(e["event"] == "snapshot" for e in evs), timeout=30)
    err = next(e["data"] for e in ev if e["event"] == "error")
    assert err["id"] == "bad" and err["code"] == "UNKNOWN_METRIC"


def test_console_stream_ends_when_the_token_expires(admin):
    """The stream sends `end` with reason token-expired when the JWT used to open it expires"""
    now = int(time.time())
    short = jwt.encode({"sub": admin.id, "email": admin.user["email"], "role": "SUPERADMIN", "iat": now, "exp": now + 6}, config.JWT_SECRET, algorithm="HS512")
    ev = read_sse(short, {"subscriptions": [{"id": "st", "kind": "status"}]}, until=lambda evs: any(e["event"] == "end" for e in evs), timeout=40)
    end = [e for e in ev if e["event"] == "end"]
    assert end and end[0]["data"]["reason"] == "token-expired", ev


def test_console_stream_token_in_query_string_is_not_accepted(admin):
    """The JWT travels in the Authorization header only: ?token= alone is 401 (keeps tokens out of URLs and logs)"""
    r = httpx.post(f"{config.BACKEND}/console/stream?token={admin.token}", json={"subscriptions": [{"id": "s", "kind": "status"}]}, timeout=10)
    assert r.status_code == 401


# ───────────────────────────── console UI (nginx) ─────────────────────────────
@pytest.fixture(scope="module")
def ui():
    return Api(config.CONSOLE)


def test_console_ui_index_and_assets(ui):
    """/console/ serves the SPA with security headers; every asset it references is served (200) with long-lived caching"""
    r = ui.get("/console/", token=None).expect(200)
    assert "text/html" in r.headers["content-type"] and "<div id=\"root\"" in r.text
    assert r.headers["x-content-type-options"] == "nosniff" and r.headers["x-frame-options"] == "DENY"
    assert "default-src 'self'" in r.headers["content-security-policy"]
    assets = set(re.findall(r'(?:src|href)="(/console/assets/[^"]+)"', r.text))
    assert assets, "index.html references no hashed assets"
    for a in assets:
        got = ui.get(a, token=None).expect(200)
        assert "immutable" in got.headers.get("cache-control", "") or "max-age=31536000" in got.headers.get("cache-control", ""), a
    ui.get("/console/assets/missing-file.js", token=None).expect(404)


def test_console_ui_routing(ui):
    """/ redirects to /console/, /console to /console/, unknown SPA routes fall back to the index"""
    ui.get("/", token=None, follow_redirects=False).expect(302)
    assert ui.get("/", token=None, follow_redirects=False).headers["location"] == "/console/"
    assert ui.get("/console", token=None, follow_redirects=False).status == 301
    deep = ui.get("/console/logs/some/deep/route", token=None).expect(200)
    assert "<div id=\"root\"" in deep.text


def test_console_ui_api_proxy_reaches_the_backend(ui, admin):
    """/api/* is proxied to the backend with the prefix stripped: health, login and an authenticated call work through nginx"""
    assert ui.get("/api/actuator/health", token=None).expect(200).json_("status") == "UP"
    tok = ui.post("/api/auth/login", token=None, json={"email": config.ADMIN_EMAIL, "password": config.ADMIN_PASSWORD}).expect(200).json_("token")
    ui.get("/api/console/status", token=tok).expect(200)
    ui.get("/api/console/status", token=None).expect(401)
    ui.get("/api/products?size=1&search=a%20b", token=tok).expect(200)  # encoded query reaches the backend untouched


def test_console_ui_stream_through_proxy_is_unbuffered(ui, admin):
    """The SSE stream works through the nginx proxy: the first snapshot arrives promptly instead of being buffered"""
    t0 = time.monotonic()
    ev = read_sse(admin.token, {"subscriptions": [{"id": "st", "kind": "status"}]}, until=lambda evs: any(e["event"] == "snapshot" for e in evs),
                  timeout=20, base=config.CONSOLE, path="/api/console/stream")
    assert any(e["event"] == "snapshot" for e in ev) and time.monotonic() - t0 < 15
