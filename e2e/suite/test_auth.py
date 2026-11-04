"""Area: authentication, JWT handling, users and the role matrix."""
import base64
import json
import time

import jwt
import pytest

from lib import config
from lib.api import Api, login
from lib.factory import uid


def _forge(claims: dict, secret: str = config.JWT_SECRET, alg: str = "HS512") -> str:
    return jwt.encode(claims, secret, algorithm=alg)


def _claims(sub: str, role: str = "SUPERADMIN", exp_in: int = 3600) -> dict:
    now = int(time.time())
    return {"sub": sub, "email": "x@x.x", "role": role, "iat": now, "exp": now + exp_in}


# ───────────────────────────── login ─────────────────────────────
@pytest.mark.parametrize("role", config.ROLES[:-1])
def test_login_every_seeded_role(role):
    """Login succeeds for every seeded role and returns a JWT plus the user's role"""
    api = login(*config.ACCOUNTS[role])
    assert api.user["role"] == role
    claims = jwt.decode(api.token, options={"verify_signature": False})
    assert claims["role"] == role and claims["sub"] == api.user["id"] and claims["email"] == api.user["email"]
    assert 0 < claims["exp"] - time.time() <= 86400 + 60  # JWT_EXPIRATION_MS=86400000 in compose


def test_login_response_never_leaks_password_hash(anon):
    """Login body and user payloads contain no password / hash"""
    r = anon.post("/auth/login", json={"email": config.ADMIN_EMAIL, "password": config.ADMIN_PASSWORD}).expect(200)
    assert "password" not in r.text.lower() and "$2a$" not in r.text and "$2b$" not in r.text


def test_login_wrong_password_and_unknown_user_look_the_same(anon):
    """Wrong password and unknown e-mail both give 401 INVALID_CREDENTIALS (no user enumeration)"""
    a = anon.post("/auth/login", json={"email": config.ADMIN_EMAIL, "password": "nope"}).expect(401, code="INVALID_CREDENTIALS")
    b = anon.post("/auth/login", json={"email": f"ghost-{uid()}@x.test", "password": "nope"}).expect(401, code="INVALID_CREDENTIALS")
    assert a.json_("error", "message") == b.json_("error", "message")


def test_login_malformed_and_missing_fields(anon):
    """Malformed JSON is 400 MALFORMED_REQUEST; missing fields never 500"""
    anon.post("/auth/login", content="{not json", headers={"Content-Type": "application/json"}).expect(400, code="MALFORMED_REQUEST")
    for body in ({}, {"email": config.ADMIN_EMAIL}, {"password": "x"}, {"email": None, "password": None}):
        r = anon.post("/auth/login", json=body)
        assert r.status in (400, 401), f"{body} -> {r.status}{r.describe()}"


def test_login_has_no_lockout_valid_login_still_works_after_failures(anon):
    """No account lockout exists: 8 bad attempts do not block the next valid login (documented behaviour)"""
    for _ in range(8):
        anon.post("/auth/login", json={"email": config.ADMIN_EMAIL, "password": "wrong"}).expect(401)
    anon.post("/auth/login", json={"email": config.ADMIN_EMAIL, "password": config.ADMIN_PASSWORD}).expect(200)


def test_login_disabled_account_is_403(admin, factory, anon):
    """A deactivated user cannot log in (403 ACCOUNT_DISABLED) and can again once reactivated"""
    u = factory.user(admin, "OPERATOR")
    admin.patch(f"/users/{u.id}", json={"active": False}).expect(200)
    anon.post("/auth/login", json={"email": u.email, "password": u.password}).expect(403, code="ACCOUNT_DISABLED")
    admin.patch(f"/users/{u.id}", json={"active": True}).expect(200)
    anon.post("/auth/login", json={"email": u.email, "password": u.password}).expect(200)


def test_deactivated_user_existing_token_is_rejected(admin, factory):
    """Deactivating a user invalidates the tokens it already holds"""
    u = factory.user(admin, "OPERATOR")
    me = login(u.email, u.password)
    me.patch("/users/me", json={"name": "still here"}).expect(200)
    admin.patch(f"/users/{u.id}", json={"active": False}).expect(200)
    assert me.patch("/users/me", json={"name": "ghost"}).status in (401, 403)


# ───────────────────────────── JWT validation ─────────────────────────────
def test_no_token_is_401_with_json_error(anon):
    """Protected endpoint without a token -> 401 UNAUTHORIZED JSON"""
    anon.get("/orders").expect(401, code="UNAUTHORIZED")


def test_jwt_tampering_is_rejected(admin, anon):
    """Altered payload (role escalation) or stripped signature -> 401"""
    op = login(*config.ACCOUNTS["OPERATOR"])
    h, p, s = op.token.split(".")
    payload = json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))
    payload["role"] = "SUPERADMIN"
    p2 = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
    for bad in (f"{h}.{p2}.{s}", f"{h}.{p}.", f"{h}.{p}.{s[:-4]}AAAA", "garbage", f"{h}.{p}"):
        anon.get("/users", token=bad).expect(401)


def test_jwt_signed_with_other_secret_is_rejected(anon):
    """A well-formed token signed with a different secret -> 401"""
    t = _forge(_claims("someone"), secret="x" * 64)
    anon.get("/orders", token=t).expect(401)


def test_jwt_alg_none_is_rejected(anon):
    """Unsigned (alg=none) token -> 401"""
    header = base64.urlsafe_b64encode(b'{"alg":"none","typ":"JWT"}').decode().rstrip("=")
    body = base64.urlsafe_b64encode(json.dumps(_claims("x")).encode()).decode().rstrip("=")
    anon.get("/orders", token=f"{header}.{body}.").expect(401)


def test_jwt_expired_is_rejected_and_valid_forged_with_real_secret_is_accepted(admin, anon):
    """Expired token -> 401; same claims with a future exp (signed by the shared secret) -> 200"""
    uid_ = admin.id
    anon.get("/orders?size=1", token=_forge(_claims(uid_, exp_in=-60))).expect(401)
    anon.get("/orders?size=1", token=_forge(_claims(uid_, exp_in=600))).expect(200)


@pytest.mark.parametrize("alg", ["HS256", "HS384", "HS512"])
def test_jwt_hmac_variants_accepted(admin, anon, alg):
    """The backend validates HS256/384/512 tokens made with the shared secret (the suggestions service relies on the same)"""
    anon.get("/orders?size=1", token=_forge(_claims(admin.id), alg=alg)).expect(200)


def test_garbage_authorization_header_does_not_break_public_endpoints(anon):
    """A stale Authorization header must not 401 a public endpoint (file download, health, login)"""
    anon.get("/actuator/health", token="garbage").expect(200)
    anon.post("/auth/login", token="garbage", json={"email": config.ADMIN_EMAIL, "password": config.ADMIN_PASSWORD}).expect(200)


# ───────────────────────────── users CRUD ─────────────────────────────
def test_user_crud_lifecycle(system, factory):
    """ADMIN_SYSTEM creates, reads, lists, updates and deactivates a user"""
    u = factory.user(system, "OPERATOR")
    assert u.data["role"] == "OPERATOR" and u.data["active"] is True and u.data["email"] == u.email
    got = system.get(f"/users/{u.id}").expect(200).body
    assert got["id"] == u.id and "password" not in json.dumps(got).lower()
    lst = system.get("/users?role=OPERATOR&size=50").expect(200)
    assert all(x["role"] == "OPERATOR" for x in lst.json_("users"))
    assert {"page", "size", "total_elements", "total_pages"} <= set(lst.json_("pagination"))
    upd = system.patch(f"/users/{u.id}", json={"name": "Renamed", "role": "DISPATCHER"}).expect(200).body
    assert upd["name"] == "Renamed" and upd["role"] == "DISPATCHER"
    system.patch(f"/users/{u.id}", json={"active": False}).expect(200)
    assert system.get(f"/users/{u.id}").json_("active") is False


def test_user_is_active_filter(system, factory):
    """GET /users?isActive/active filter narrows to inactive users"""
    u = factory.user(system, "OPERATOR")
    system.patch(f"/users/{u.id}", json={"active": False}).expect(200)
    r = system.get("/users?isActive=false&size=50").expect(200)
    users = r.json_("users")
    assert users and all(x["active"] is False for x in users), r.describe()


def test_user_validation_and_conflicts(system, factory):
    """Duplicate e-mail 409, bad e-mail/blank fields 400 VALIDATION_ERROR, unknown role 400, unknown id 404"""
    u = factory.user(system, "OPERATOR")
    system.post("/users", json={"email": u.email, "name": "dup", "role": "OPERATOR", "initial_password": "Passw0rd!x1"}).expect(409, code="EMAIL_ALREADY_EXISTS")
    system.post("/users", json={"email": "not-an-email", "name": "n", "role": "OPERATOR", "initial_password": "x"}).expect(400, code="VALIDATION_ERROR")
    system.post("/users", json={"email": f"{uid()}@x.test", "name": "", "role": "OPERATOR", "initial_password": "x"}).expect(400, code="VALIDATION_ERROR")
    system.post("/users", json={"email": f"{uid()}@x.test", "name": "n", "role": "WIZARD", "initial_password": "x"}).expect(400)
    system.get("/users/does-not-exist").expect(404, code="USER_NOT_FOUND")
    system.patch("/users/does-not-exist", json={"name": "x"}).expect(404, code="USER_NOT_FOUND")


def test_unknown_role_has_stable_error_code(system):
    """Creating a user with an unknown role answers INVALID_ROLE without leaking class names"""
    r = system.post("/users", json={"email": f"{uid()}@x.test", "name": "n", "role": "WIZARD", "initial_password": "x"}).expect(400)
    assert r.code == "INVALID_ROLE" and "com.usal" not in r.text


def test_reset_password_by_admin(system, factory, anon):
    """ADMIN_SYSTEM resets a user's password (204); the old one stops working, the new one works"""
    u = factory.user(system, "OPERATOR")
    system.post(f"/users/{u.id}/reset-password", json={"new_password": "Brand-new-1!"}).expect(204)
    anon.post("/auth/login", json={"email": u.email, "password": u.password}).expect(401)
    anon.post("/auth/login", json={"email": u.email, "password": "Brand-new-1!"}).expect(200)
    system.post(f"/users/{u.id}/reset-password", json={}).expect(400, code="VALIDATION_ERROR")
    system.post("/users/nope/reset-password", json={"new_password": "x"}).expect(404)


def test_change_own_password(system, factory, anon):
    """Self-service change: wrong current -> 400 WRONG_CURRENT_PASSWORD, same -> 400 SAME_PASSWORD, ok -> new login works"""
    u = factory.user(system, "OPERATOR")
    me = login(u.email, u.password)
    me.post("/users/me/change-password", json={"current_password": "wrong", "new_password": "Other-pass-1"}).expect(400, code="WRONG_CURRENT_PASSWORD")
    me.post("/users/me/change-password", json={"current_password": u.password, "new_password": u.password}).expect(400, code="SAME_PASSWORD")
    me.post("/users/me/change-password", json={"current_password": u.password}).expect(400, code="VALIDATION_ERROR")
    me.post("/users/me/change-password", json={"current_password": u.password, "new_password": "Other-pass-1"}).expect(200)
    anon.post("/auth/login", json={"email": u.email, "password": u.password}).expect(401)
    anon.post("/auth/login", json={"email": u.email, "password": "Other-pass-1"}).expect(200)
    anon.post("/users/me/change-password", json={"current_password": "a", "new_password": "b"}).expect(401)


def test_update_me_profile(system, factory):
    """PATCH /users/me changes own name and address; role and e-mail stay put"""
    u = factory.user(system, "OPERATOR")
    me = login(u.email, u.password)
    r = me.patch("/users/me", json={"name": "New Name", "address": {"street": "Calle 1", "department": "D", "floor": "2", "postalCode": "11111"}}).expect(200).body
    assert r["name"] == "New Name" and r["role"] == "OPERATOR" and r["email"] == u.email
    assert r["address"]["street"] == "Calle 1"
    # fields that are not part of the contract are ignored, not applied
    r2 = me.patch("/users/me", json={"role": "SUPERADMIN", "email": "evil@x.test"}).expect(200).body
    assert r2["role"] == "OPERATOR" and r2["email"] == u.email


def test_provider_supplier_link(system, factory):
    """A PROVIDER account carries a supplier; PATCH sets it and a blank value clears it"""
    u = factory.user(system, "PROVIDER", supplier="ACME")
    assert system.get(f"/users/{u.id}").json_("supplier") == "ACME"
    system.patch(f"/users/{u.id}", json={"supplier": "Globex"}).expect(200)
    assert system.get(f"/users/{u.id}").json_("supplier") == "Globex"
    system.patch(f"/users/{u.id}", json={"supplier": ""}).expect(200)
    assert system.get(f"/users/{u.id}").json_("supplier") in (None, "")


# ───────────────────────────── role matrix ─────────────────────────────
# endpoint -> roles that may call it (everyone else must get 403; no token 401).
def _probe(method, path, body=None):
    return (method, path, body)


MATRIX = {
    "users list": (_probe("GET", "/users"), {"SUPERADMIN", "ADMIN_SYSTEM"}),
    "orders list": (_probe("GET", "/orders?size=1"), set(config.ROLES)),  # any authenticated role reads
    "products list": (_probe("GET", "/products?size=1"), set(config.ROLES)),
    "vehicles list": (_probe("GET", "/vehicles"), {"SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE"}),
    "zones list": (_probe("GET", "/warehouse/zones"), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "positions list": (_probe("GET", "/warehouse/positions"), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "metrics catalog": (_probe("GET", "/metrics/catalog"), {"SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "DASHBOARD"}),
    "query catalog": (_probe("GET", "/query/catalog"), set(config.ROLES)),
    "restock orders list": (_probe("GET", "/restock/orders"), {"SUPERADMIN", "ADMIN_WAREHOUSE", "PROVIDER"}),
    "receptions list": (_probe("GET", "/restock/receptions"), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "create reception": (_probe("POST", "/restock/receptions", {"product_id": "none", "quantity_received": 1, "delivery_unit": "CAJA", "supplier": "s"}),
                         {"SUPERADMIN", "ADMIN_WAREHOUSE", "PROVIDER"}),
    "console status": (_probe("GET", "/console/status"), {"SUPERADMIN"}),
    "files listing": (_probe("GET", "/api/v1/files"), {"SUPERADMIN"}),
    "create order": (_probe("POST", "/orders", {}), {"SUPERADMIN", "ADMIN_SALES", "ADMIN_WAREHOUSE", "OPERATOR"}),
    "create product": (_probe("POST", "/products", {"sku": "X", "name": "x", "category": "OTROS", "height": 1, "width": 1, "length": 1, "weight": 1}), {"SUPERADMIN", "ADMIN_WAREHOUSE", "ADMIN_SALES"}),
    "delete product": (_probe("DELETE", "/products/none"), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "create vehicle": (_probe("POST", "/vehicles", {}), {"SUPERADMIN", "ADMIN_SYSTEM"}),
    "create zone": (_probe("POST", "/warehouse/zones", {"zone_code": "MATRIX-NEVER", "max_allowed_lines": 1}), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "create restock order": (_probe("POST", "/restock/orders", {"product_id": "none", "quantity_requested": 1, "supplier": "s"}), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "apply restock suggestions": (_probe("POST", "/metrics/restock-suggestions/apply", {}), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "internal assign vehicle": (_probe("PATCH", "/internal/orders/none/assign-vehicle", {}), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "internal patch vehicle": (_probe("PATCH", "/internal/vehicles/none", {}), {"SUPERADMIN", "ADMIN_SYSTEM"}),
    "stock adjustment": (_probe("POST", "/warehouse/positions/none/stock-adjustments", {"quantity": 1}), {"SUPERADMIN", "ADMIN_WAREHOUSE"}),
    "product adjustments history": (_probe("GET", "/products/none/stock-adjustments"), {"SUPERADMIN", "ADMIN_WAREHOUSE", "ADMIN_SALES"}),
}


@pytest.mark.parametrize("name", MATRIX)
def test_role_matrix(role_apis, anon, name):
    """Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401"""
    (method, path, body), allowed = MATRIX[name]
    kw = {"json": body} if body is not None else {}
    assert anon.request(method, path, token=None, **kw).status == 401, f"{name}: anonymous"
    wrong = []
    for role, api in role_apis.items():
        r = api.request(method, path, **kw)
        if role in allowed and r.status in (401, 403):
            wrong.append(f"{role} should pass authorization but got {r.status}")
        if role not in allowed and r.status != 403:
            wrong.append(f"{role} should be 403 but got {r.status} {r.text[:120]}")
    assert not wrong, f"{name} [{method} {path}]:\n  " + "\n  ".join(wrong)


def test_authorization_is_decided_before_validation(operator):
    """An unauthorized role is told 403 even when its body is invalid (not 400)"""
    operator.post("/products", json={}).expect(403)
    operator.post("/warehouse/zones", json={}).expect(403)


def test_error_shape_is_uniform_for_403(operator):
    """403 bodies are {"error": {"code": "ACCESS_DENIED", "message"}}"""
    r = operator.get("/users").expect(403)
    assert r.code == "ACCESS_DENIED" and r.json_("error", "message")
