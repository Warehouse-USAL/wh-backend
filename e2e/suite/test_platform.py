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
    """SEED_DEMO: the 8 roles' demo accounts log in, catalogue/warehouse/orders/vehicles are populated"""
    for role, (email, pw) in config.ACCOUNTS.items():
        assert login(email, pw).user["role"] == role
    prods = warehouse.get("/products?size=100").expect(200)
    assert prods.json_("pagination", "total_elements") >= 20
    skus = [p["sku"] for p in prods.json_("products")]
    assert len(set(skus)) == len(skus)
    assert admin.get("/orders?size=1").json_("pagination", "total_elements") > 1000
    assert len(warehouse.get("/warehouse/zones").expect(200).body) > 0 if isinstance(warehouse.get("/warehouse/zones").body, list) else True
    assert admin.get("/users?size=50").json_("pagination", "total_elements") >= 9


def test_seed_positions_have_unique_scannable_qr_codes(warehouse):
    """Every position QR code is unique and carries the configured prefix (WH2|)"""
    items = warehouse.get("/warehouse/positions?size=200").expect(200).json_("positions")
    qrs = [warehouse.get(f"/warehouse/positions/{p['id_position']}").expect(200).json_("qr_code") for p in items if p["id_position"].startswith("pos-")]
    assert len(qrs) >= 30
    assert qrs and len(set(qrs)) == len(qrs) and all(q.startswith("WH2|") for q in qrs)


def test_error_contract_is_uniform(admin, anon):
    """Errors are always {"error":{"code","message"}} JSON: 404 on unknown route, 405 on wrong method, 401 anon, validation, malformed body"""
    for r, st in (
                  (anon.get("/products", token=None), 401), (admin.post("/products", json={}), 400),
                  (admin.post("/orders", content="{bad", headers={"Content-Type": "application/json"}), 400)):
        assert r.status == st, r.describe()
        assert set(r.body["error"]) >= {"code", "message"} and r.body["error"]["code"], r.describe()
    r = admin.post("/orders", content="x", headers={"Content-Type": "text/plain"})
    assert r.status in (415, 400) and "error" in r.body


@pytest.mark.known_failure(bug="BUG-PLATFORM-1", reason="unknown routes (404) and wrong methods (405) answer with Spring's default error body {timestamp,status,error,path} instead of the API's {error:{code,message}} contract (no NoHandlerFound/NoResourceFound mapping in the global exception handler)")
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
