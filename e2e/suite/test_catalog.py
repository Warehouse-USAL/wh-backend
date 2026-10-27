"""Area: catalog (products, categories, product location) and file storage through MinIO."""
import io
import struct
import zlib

import pytest

from lib.factory import uid

PNG = (b"\x89PNG\r\n\x1a\n" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
       + struct.pack(">I", zlib.crc32(b"IHDR" + struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)) & 0xFFFFFFFF)
       + b"\x00\x00\x00\x00IEND\xaeB`\x82")
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32
WEBP = b"RIFF\x24\x00\x00\x00WEBPVP8 " + b"\x00" * 32


def _upload(api, data=PNG, ctype="image/png", name="a.png", **params):
    return api.post("/api/v1/files/upload", files={"file": (name, io.BytesIO(data), ctype)}, params=params)


# ───────────────────────────── products ─────────────────────────────
def test_product_create_and_read_back(warehouse, factory):
    """Create a product with price/specs/images/dimensions and read it back (volume computed, stock zeroed)"""
    sku = f"E2E-{uid(10).upper()}"
    created = warehouse.post("/products", json={
        "sku": sku, "name": "Full product", "description": "d", "category": "tecnologia",
        "images": [{"url": "https://example.test/a.jpg", "alt": "front", "is_primary": True}],
        "price": {"amount_cents": 12345, "currency": "ARS", "tax_included": False},
        "specs": [{"label": "Color", "value": "Negro"}], "max_quantity_per_order": 7, "minimum_stock": 3,
        "height": 2, "width": 3, "length": 4, "weight": 0.5}).expect(201).json_("product")
    assert created["category"] == "TECNOLOGIA", "category is normalised to upper case"
    got = warehouse.get(f"/products/{created['id']}").expect(200).json_("product")
    assert got["sku"] == sku and got["volume"] == 24.0 and got["active"] is True
    assert got["price"] == {"amount_cents": 12345, "currency": "ARS", "tax_included": False}
    assert got["specs"] == [{"label": "Color", "value": "Negro"}]
    assert got["images"][0]["is_primary"] is True
    assert got["stock"] == {"available": 0, "reserved": 0, "physical": 0, "min": 3}
    assert got["order_constraints"] == {"max_quantity_per_order": 7}


def test_product_sku_must_be_unique(warehouse, factory):
    """Duplicate SKU -> 409 SKU_ALREADY_EXISTS"""
    p = factory.product()
    warehouse.post("/products", json={"sku": p["sku"], "name": "x", "category": "OTROS", "height": 1, "width": 1,
                                      "length": 1, "weight": 1}).expect(409, code="SKU_ALREADY_EXISTS")


@pytest.mark.parametrize("patch", [
    {"height": -1}, {"width": -0.5}, {"length": -3}, {"weight": -1},
    {"minimum_stock": -1}, {"max_quantity_per_order": -1}, {"sku": ""}, {"name": ""}, {"category": ""},
])
def test_product_create_validation(warehouse, patch):
    """Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR"""
    body = {"sku": f"E2E-{uid(10)}", "name": "n", "category": "OTROS", "height": 1, "width": 1, "length": 1, "weight": 1}
    body.update(patch)
    warehouse.post("/products", json=body).expect(400, code="VALIDATION_ERROR")


def test_product_create_missing_dimensions_and_bad_category(warehouse):
    """Missing dimensions -> VALIDATION_ERROR; unknown category -> INVALID_CATEGORY"""
    warehouse.post("/products", json={"sku": f"E2E-{uid()}", "name": "n", "category": "OTROS"}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/products", json={"sku": f"E2E-{uid()}", "name": "n", "category": "NOPE", "height": 1, "width": 1,
                                      "length": 1, "weight": 1}).expect(400, code="INVALID_CATEGORY")


def test_product_zero_max_quantity_means_unlimited(warehouse, factory, operator):
    """max_quantity_per_order = 0 imposes no per-order limit"""
    s = factory.stocked(30, max_quantity_per_order=0)
    factory.order(operator, [(s.id, 25)])


def test_product_categories(warehouse):
    """GET /products/categories lists the accepted catalogue categories"""
    cats = warehouse.get("/products/categories").expect(200).json_("categories")
    assert {"TECNOLOGIA", "HERRAMIENTAS", "ALIMENTOS", "OTROS"} <= set(cats)


def test_product_list_filters_search_and_pagination(warehouse, factory):
    """List: category filter, search by name/SKU/description (literal, not regex), page size capped at 50, pagination block"""
    tag = uid(8)
    a = factory.product(name=f"Zebra {tag} alpha", category="HERRAMIENTAS")
    factory.product(name=f"Zebra {tag} beta", category="ALIMENTOS")
    found = warehouse.get(f"/products?search=zebra {tag}&size=50").expect(200)
    assert found.json_("pagination", "total_elements") == 2
    only = warehouse.get(f"/products?search={tag}&category=herramientas").expect(200).json_("products")
    assert [p["id"] for p in only] == [a["id"]]
    assert warehouse.get(f"/products?search={a['sku']}").json_("products")[0]["id"] == a["id"]
    assert warehouse.get("/products?search=.*").json_("pagination", "total_elements") == 0  # regex is escaped
    big = warehouse.get("/products?size=500").expect(200)
    assert big.json_("pagination", "size") == 50 and len(big.json_("products")) <= 50
    p1 = warehouse.get("/products?size=2&page=1").expect(200)
    assert p1.json_("pagination", "page") == 1
    warehouse.get("/products?category=NOPE").expect(400, code="INVALID_CATEGORY")


def test_product_patch_partial_update(warehouse, factory):
    """PATCH changes only the given fields; unknown id 404; negative values 400"""
    p = factory.product(name="Before", minimum_stock=1)
    out = warehouse.patch(f"/products/{p['id']}", json={"name": "After", "price": {"amount_cents": 5, "currency": "USD", "tax_included": True},
                                                          "minimum_stock": 9, "specs": [{"label": "k", "value": "v"}]}).expect(200).json_("product")
    assert out["name"] == "After" and out["stock"]["min"] == 9 and out["price"]["amount_cents"] == 5
    assert out["sku"] == p["sku"] and out["category"] == p["category"] and out["height"] == p["height"]
    warehouse.patch(f"/products/{p['id']}", json={"height": -1}).expect(400, code="VALIDATION_ERROR")
    warehouse.patch(f"/products/{p['id']}", json={"category": "NOPE"}).expect(400, code="INVALID_CATEGORY")
    warehouse.patch("/products/nope", json={"name": "x"}).expect(404, code="PRODUCT_NOT_FOUND")


def test_product_deactivate_hides_it(warehouse, factory):
    """PATCH is_active=false hides the product from GET (404) and the default list; is_active=false listing shows it"""
    p = factory.product()
    warehouse.patch(f"/products/{p['id']}", json={"is_active": False}).expect(200)
    warehouse.get(f"/products/{p['id']}").expect(404, code="PRODUCT_NOT_FOUND")
    assert not warehouse.get(f"/products?search={p['sku']}").json_("products")
    hidden = warehouse.get(f"/products?search={p['sku']}&isActive=false").json_("products")
    assert [x["id"] for x in hidden] == [p["id"]]
    assert warehouse.get(f"/products/{p['id']}?isActive=false").status == 200


def test_product_delete_unassigns_positions_and_hides(warehouse, factory, sales):
    """DELETE /products/{id}: 204, product disappears, its positions are emptied and unassigned; sales cannot delete"""
    s = factory.stocked(15)
    sales.delete(f"/products/{s.id}").expect(403)
    warehouse.delete(f"/products/{s.id}").expect(204)
    warehouse.get(f"/products/{s.id}").expect(404)
    pos = warehouse.get(f"/warehouse/positions/{s.position['id_position']}").expect(200).body
    assert pos["product_id"] is None and pos["current_stock"] == 0
    warehouse.delete("/products/nope").expect(404, code="PRODUCT_NOT_FOUND")


def test_product_location_lists_positions_and_total(warehouse, factory):
    """GET /products/{id}/location returns each active position holding it and the total stock"""
    s = factory.stocked(7, positions=2)
    loc = warehouse.get(f"/products/{s.id}/location").expect(200).body
    assert loc["total_stock"] == 14 and len(loc["locations"]) == 2
    entry = loc["locations"][0]
    assert {"id_position", "position_name", "current_stock", "zone_code", "number_line"} <= set(entry), entry
    warehouse.patch(f"/warehouse/positions/{s.positions[1]['id_position']}", json={"is_active": False}).expect(200)
    assert warehouse.get(f"/products/{s.id}/location").json_("total_stock") == 7
    warehouse.get("/products/nope/location").expect(404, code="PRODUCT_NOT_FOUND")


def test_product_stock_numbers_physical_reserved_available(warehouse, factory, operator):
    """stock.physical = units in active positions; pending orders raise reserved and lower available"""
    s = factory.stocked(10)
    assert factory.stock(s.id) == {"available": 10, "reserved": 0, "physical": 10, "min": 0}
    factory.order(operator, [(s.id, 4)])
    assert factory.stock(s.id) == {"available": 6, "reserved": 4, "physical": 10, "min": 0}


# ───────────────────────────── files / MinIO ─────────────────────────────
def test_upload_serve_and_delete_roundtrip(warehouse, admin, anon):
    """Upload a PNG to MinIO, download it publicly with the right headers, list it (SUPERADMIN), delete it"""
    r = _upload(warehouse).expect(200)
    url, key = r.json_("url"), r.json_("key")
    assert key.startswith("images/") and key.endswith(".png"), r.describe()
    got = anon.get(f"/api/v1/files/{key}", token=None).expect(200)
    assert got.content == PNG and got.headers["content-type"] == "image/png"
    assert "max-age" in got.headers.get("cache-control", "")
    assert url.endswith(key)
    folder, filename = key.split("/")
    listed = admin.get("/api/v1/files").expect(200).json_("files")
    assert any(f["path"] == folder and f["filename"] == filename and f["size"] == len(PNG) for f in listed)
    warehouse.delete(f"/api/v1/files/{key}").expect(204)
    anon.get(f"/api/v1/files/{key}", token=None).expect(404)


@pytest.mark.parametrize("data,ctype,name", [(JPEG, "image/jpeg", "a.jpg"), (WEBP, "image/webp", "a.webp")])
def test_upload_other_formats(sales, data, ctype, name):
    """JPEG and WebP are accepted (sales may upload)"""
    r = _upload(sales, data, ctype, name).expect(200)
    assert r.json_("key").endswith({"image/jpeg": ".jpg", "image/webp": ".webp"}[ctype])


def test_upload_rejections(warehouse):
    """Empty file, unsupported type, content that doesn't match its declared type -> 400"""
    _upload(warehouse, b"", "image/png").expect(400)
    _upload(warehouse, b"hello", "text/plain", "a.txt").expect(400)
    _upload(warehouse, b"not really a png at all", "image/png").expect(400)
    _upload(warehouse, PNG, "image/jpeg", "a.jpg").expect(400)


def test_upload_size_limit_is_413(warehouse):
    """A file over 5 MB is refused with 413 PAYLOAD_TOO_LARGE"""
    _upload(warehouse, PNG + b"\x00" * (5 * 1024 * 1024 + 10)).expect(413, code="PAYLOAD_TOO_LARGE")


def test_upload_custom_path_and_traversal(warehouse):
    """The path part is one safe segment: 'banners' lands under banners/, '../x' or a long name is a 400"""
    r = _upload(warehouse, path="banners").expect(200)
    assert r.json_("key").startswith("banners/")
    for bad in ("../etc", "a/b", "x" * 65, "a b", "a.b"):
        _upload(warehouse, path=bad).expect(400)


def test_file_serving_unknown_and_traversal(anon):
    """Unknown file 404; traversal attempts never succeed (400/404)"""
    anon.get("/api/v1/files/images/does-not-exist.png", token=None).expect(404)
    assert anon.get("/api/v1/files/..%2f..%2fetc/passwd", token=None).status in (400, 404)
    assert anon.get("/api/v1/files/images/..%5c..%5cx.png", token=None).status in (400, 404)


def test_file_permissions(operator, dispatcher, sales, anon, warehouse):
    """Upload: ADMIN_SALES/WAREHOUSE/SUPERADMIN only; delete: WAREHOUSE/SUPERADMIN only; listing: SUPERADMIN only"""
    _upload(operator).expect(403)
    _upload(dispatcher).expect(403)
    key = _upload(sales).expect(200).json_("key")
    sales.delete(f"/api/v1/files/{key}").expect(403)
    sales.get("/api/v1/files").expect(403)
    anon.get("/api/v1/files").expect(401)
    assert _upload(anon).status in (401, 403) and anon.delete(f"/api/v1/files/{key}").status in (401, 403)
    warehouse.delete(f"/api/v1/files/{key}").expect(204)
    warehouse.delete(f"/api/v1/files/{key}").expect(404)


@pytest.mark.known_failure(bug="BUG-FILES-1", reason="/api/v1/files/** is permitAll in SecurityConfig (downloads are public), so an anonymous upload/delete reaches @PreAuthorize and gets 403 ACCESS_DENIED instead of 401 UNAUTHORIZED; only GET /api/v1/files was special-cased")
def test_file_write_without_token_is_401(anon, warehouse):
    """Anonymous upload/delete is 401 (unauthenticated), not 403 (authenticated but forbidden)"""
    _upload(anon).expect(401)
    anon.delete("/api/v1/files/images/x.png").expect(401)


def test_product_image_flow_and_cleanup_on_delete(warehouse, anon, factory):
    """Upload an image, attach it to a product; replacing it or deleting the product removes the object from MinIO"""
    k1 = _upload(warehouse).expect(200).json_("key")
    k2 = _upload(warehouse).expect(200).json_("key")
    p = factory.product(images=[{"url": f"/api/v1/files/{k1}", "alt": "one", "is_primary": True}])
    anon.get(f"/api/v1/files/{k1}", token=None).expect(200)
    warehouse.patch(f"/products/{p['id']}", json={"images": [{"url": f"/api/v1/files/{k2}", "alt": "two", "is_primary": True}]}).expect(200)
    anon.get(f"/api/v1/files/{k1}", token=None).expect(404)  # replaced image removed from storage
    anon.get(f"/api/v1/files/{k2}", token=None).expect(200)
    warehouse.delete(f"/products/{p['id']}").expect(204)
    anon.get(f"/api/v1/files/{k2}", token=None).expect(404)  # product deleted -> image removed
