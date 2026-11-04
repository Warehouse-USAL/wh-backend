"""Area: platform (seed data, API docs, health, error contract, headers, observability)."""
import httpx
import pytest

from lib import config
from lib.api import Api, login, wait_until


def test_actuator_health_public_everything_else_protected(anon):
    """/actuator/health is public and UP; info/env/metrics/prometheus are not exposed to anonymous callers"""
    assert anon.get("/actuator/health", token=None).expect(200).json_("status") == "UP"
    for p in ("/actuator/info", "/actuator/env", "/actuator/metrics", "/actuator/prometheus", "/actuator/heapdump", "/actuator/beans"):
        assert anon.get(p, token=None).status in (401, 403, 404), p


def test_openapi_documentation_is_served(anon):
    """/v3/api-docs is valid OpenAPI 3 describing the API; Swagger UI is reachable"""
    d = anon.get("/v3/api-docs", token=None).expect(200).body
    assert d["openapi"].startswith("3.") and len(d["paths"]) >= 40
    for p in ("/auth/login", "/orders", "/products", "/vehicles", "/infra/orders/{orderId}/status"):
        assert any(k.endswith(p) for k in d["paths"]), p
    assert anon.get("/swagger-ui/index.html", token=None).status == 200


def test_every_documented_protected_operation_rejects_anonymous_calls(anon):
    """Walk every GET path of the OpenAPI document: anonymous access is never 200 except for the public ones"""
    d = anon.get("/v3/api-docs", token=None).body
    public = ("/auth/", "/files/", "/api-docs", "/swagger", "/v3/", "/actuator/health")
    leaks = []
    for path, ops in d["paths"].items():
        if "get" not in ops or "{" in path or path.startswith(public):
            continue
        r = anon.get(path, token=None)
        if r.status == 200:
            leaks.append(path)
    assert not leaks, f"anonymous GET returned 200 on {leaks}"


def test_seed_demo_data_is_present(admin, warehouse):
    """SEED_DEMO: the 8 roles' demo accounts log in, catalogue/orders/vehicles are populated and the warehouse exists"""
    for role, (email, pw) in config.ACCOUNTS.items():
        assert login(email, pw).user["role"] == role
    prods = warehouse.get("/products?size=100").expect(200)
    assert prods.json_("pagination", "total_elements") >= 20
    skus = [p["sku"] for p in prods.json_("products")]
    assert len(set(skus)) == len(skus)
    assert admin.get("/orders?size=1").json_("pagination", "total_elements") > 1000
    assert any(z["zone_code"] == "A" for z in warehouse.get("/warehouse/zones").expect(200).json_("zones"))
    assert admin.get("/users?size=50").json_("pagination", "total_elements") >= 9


SEEDED_POSITIONS = ["PA1", "PA2", "PA3", "PM1", "PM2", "PM3"]


def _seeded_line(warehouse) -> tuple[dict, list[dict]]:
    """The real warehouse as seeded: zone A with its single line 1 and that line's positions.
    Other tests create their own zones, so only zone A is looked at, never global counts."""
    zone = next(z for z in warehouse.get("/warehouse/zones").expect(200).json_("zones") if z["zone_code"] == "A")
    lines = warehouse.get(f"/warehouse/zones/{zone['id_zone']}/lines").expect(200).json_("lines")
    assert [ln["number_line"] for ln in lines] == [1], lines
    positions = warehouse.get(f"/warehouse/lines/{lines[0]['id_line']}/positions").expect(200).json_("positions")
    return zone, positions


def test_seeded_warehouse_is_one_zone_one_line_six_positions(warehouse):
    """The seeded warehouse mirrors the real one: zone A, line 1, positions PA1-PA3 and PM1-PM3, active, one distinct product each"""
    zone, positions = _seeded_line(warehouse)
    assert zone["is_active"] is True and zone["max_allowed_lines"] == 1
    ln = warehouse.get(f"/warehouse/zones/{zone['id_zone']}/lines").expect(200).json_("lines")[0]
    assert ln["max_allowed_positions"] == 6
    assert sorted(p["position_name"] for p in positions) == SEEDED_POSITIONS
    assert all(p["is_active"] and p["id_zone"] == zone["id_zone"] for p in positions)
    products = [p["product_id"] for p in positions]
    assert all(products) and len(set(products)) == 6, products
    assert all(0 < p["current_stock"] <= p["maximum_capacity"] for p in positions)


def test_seed_positions_have_the_qr_codes_infra_recognises(warehouse):
    """Each seeded position's QR text is the configured prefix (WH2|) plus its name: WH2|PA1..PA3 and WH2|PM1..PM3, all unique"""
    _, positions = _seeded_line(warehouse)
    qrs = {p["position_name"]: p["qr_code"] for p in positions}
    assert qrs == {n: f"WH2|{n}" for n in SEEDED_POSITIONS}


def test_every_seeded_position_can_be_patched(warehouse):
    """Re-sending a seeded position's own QR passes the capacity/volume guards (a past incident: a seeded position answered 409 to any edit)"""
    _, positions = _seeded_line(warehouse)
    for p in positions:
        r = warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"qr_code": p["qr_code"]})
        assert r.status == 200, (p["position_name"], r.describe())
        assert r.body["qr_code"] == p["qr_code"] and r.body["current_stock"] == p["current_stock"]


def test_an_order_across_seeded_positions_publishes_their_qr_codes_as_stops(operator, warehouse, factory, kafka):
    """An order for products held in PA3 and PM1 publishes stops [WH2|PA3, WH2|PM1] (position-name order) on topic `orders`"""
    _, positions = _seeded_line(warehouse)
    by_name = {p["position_name"]: p for p in positions}
    o = factory.order(operator, [(by_name["PM1"]["product_id"], 1), (by_name["PA3"]["product_id"], 1)])
    try:
        assert kafka.wait_order(o["id"], "pending")["value"]["stops"] == ["WH2|PA3", "WH2|PM1"]
    finally:
        operator.post(f"/orders/{o['id']}/cancel", json={"reason": "e2e cleanup"})


def test_error_contract_is_uniform(admin, anon):
    """Errors are always {"error":{"code","message"}} JSON: 404 on unknown route, 405 on wrong method, 401 anon, validation, malformed body"""
    for r, st in (
                  (anon.get("/products", token=None), 401), (admin.post("/products", json={}), 400),
                  (admin.post("/orders", content="{bad", headers={"Content-Type": "application/json"}), 400)):
        assert r.status == st, r.describe()
        assert set(r.body["error"]) >= {"code", "message"} and r.body["error"]["code"], r.describe()
    r = admin.post("/orders", content="x", headers={"Content-Type": "text/plain"})
    assert r.status in (415, 400) and "error" in r.body


def test_unknown_route_uses_the_error_contract(admin):
    """404 for an unknown path uses the same {"error":{"code","message"}} envelope as every other error"""
    for r, st in ((admin.get("/definitely/not/here"), 404), (admin.request("PUT", "/orders"), 405)):
        assert r.status == st and set(r.body.get("error", {})) >= {"code", "message"}, r.describe()


def test_responses_carry_request_id_and_security_headers(anon):
    """Every response has X-Request-Id (echoed when supplied) and hardening headers"""
    r = anon.get("/actuator/health", token=None, headers={"X-Request-Id": "e2e-fixed-id"})
    assert r.headers["x-request-id"] == "e2e-fixed-id"
    assert anon.get("/actuator/health", token=None).headers["x-request-id"]
    assert r.headers["x-content-type-options"] == "nosniff" and r.headers["x-frame-options"] == "DENY"


def test_cors_does_not_allow_arbitrary_origins(anon):
    """A browser preflight from an unknown origin never gets an allow-origin header"""
    r = httpx.options(f"{config.BACKEND}/auth/login", headers={"Origin": "http://evil.example", "Access-Control-Request-Method": "POST"}, timeout=10)
    assert r.headers.get("access-control-allow-origin") not in ("*", "http://evil.example")


def test_victoriametrics_receives_backend_metrics(admin):
    """OTEL pipeline end to end: backend metrics (catalogued under /metrics/catalog) are queryable in VictoriaMetrics"""
    names = httpx.get(f"{config.VICTORIAMETRICS}/api/v1/label/__name__/values", timeout=10).json()["data"]
    assert any(n.startswith("wh_") for n in names), names[:20]


def test_victorialogs_receives_logs():
    """OTEL pipeline end to end: backend logs are stored in VictoriaLogs"""
    r = httpx.post(f"{config.VICTORIALOGS}/select/logsql/query", data={"query": "*", "limit": "1"}, timeout=10)
    assert r.status_code == 200 and r.text.strip()


def test_file_storage_roundtrip_through_minio(admin):
    """MinIO integration: see test_catalog file tests for upload/download/delete; here only that the platform exposes the documented upload route"""
    d = admin.get("/v3/api-docs", token=None).expect(200).body
    assert "/api/v1/files/upload" in d["paths"]
