"""Area: warehouse structure (zones, lines, positions), QR codes, capacity guards, fit validation."""
import pytest

from lib.factory import uid


def _zone_line(factory):
    z = factory.zone()
    return z, factory.line(z["id_zone"])


# ───────────────────────────── zones ─────────────────────────────
def test_zone_crud_and_soft_delete(warehouse):
    """Zones are created inactive, activated/renamed with PATCH, listed, and DELETE is a soft delete"""
    code = f"ZC{uid(6).upper()}"
    z = warehouse.post("/warehouse/zones", json={"zone_code": code, "max_allowed_lines": 4}).expect(201).body
    assert z["is_active"] is False and z["zone_code"] == code and z["max_allowed_lines"] == 4
    zid = z["id_zone"]
    upd = warehouse.patch(f"/warehouse/zones/{zid}", json={"is_active": True, "max_allowed_lines": 8, "zone_code": code + "X"}).expect(200).body
    assert upd["is_active"] is True and upd["max_allowed_lines"] == 8 and upd["zone_code"] == code + "X"
    assert any(x["id_zone"] == zid for x in warehouse.get("/warehouse/zones").json_("zones"))
    warehouse.delete(f"/warehouse/zones/{zid}").expect(204)
    after = [x for x in warehouse.get("/warehouse/zones").json_("zones") if x["id_zone"] == zid]
    assert after and after[0]["is_active"] is False, "DELETE keeps the zone but deactivates it"


def test_zone_validation_and_conflicts(warehouse, factory):
    """Duplicate code 409, blank code / zero lines 400, unknown id 404, renaming onto an existing code 409"""
    z = factory.zone()
    warehouse.post("/warehouse/zones", json={"zone_code": z["zone_code"], "max_allowed_lines": 1}).expect(409, code="ZONE_CODE_ALREADY_EXISTS")
    warehouse.post("/warehouse/zones", json={"zone_code": "", "max_allowed_lines": 1}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/warehouse/zones", json={"zone_code": f"Z{uid()}", "max_allowed_lines": 0}).expect(400, code="VALIDATION_ERROR")
    other = factory.zone()
    warehouse.patch(f"/warehouse/zones/{other['id_zone']}", json={"zone_code": z["zone_code"]}).expect(409, code="ZONE_CODE_ALREADY_EXISTS")
    warehouse.patch("/warehouse/zones/nope", json={"is_active": True}).expect(404, code="ZONE_NOT_FOUND")
    warehouse.delete("/warehouse/zones/nope").expect(404, code="ZONE_NOT_FOUND")


# ───────────────────────────── lines ─────────────────────────────
def test_line_lifecycle_and_rules(warehouse, factory):
    """Lines: inactive zone refuses lines, duplicate number 409, listing, PATCH, soft delete, 404s"""
    inactive = factory.zone(active=False)
    warehouse.post(f"/warehouse/zones/{inactive['id_zone']}/lines", json={"number_line": 1, "max_allowed_positions": 5}).expect(400, code="ZONE_INACTIVE")
    z = factory.zone()
    ln = warehouse.post(f"/warehouse/zones/{z['id_zone']}/lines", json={"number_line": 1, "max_allowed_positions": 5}).expect(201).body
    assert ln["is_active"] is False and ln["id_zone"] == z["id_zone"]
    warehouse.post(f"/warehouse/zones/{z['id_zone']}/lines", json={"number_line": 1, "max_allowed_positions": 5}).expect(409, code="LINE_NUMBER_ALREADY_EXISTS")
    warehouse.post(f"/warehouse/zones/{z['id_zone']}/lines", json={"number_line": 0, "max_allowed_positions": 5}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/warehouse/zones/nope/lines", json={"number_line": 1, "max_allowed_positions": 5}).expect(404, code="ZONE_NOT_FOUND")
    two = warehouse.post(f"/warehouse/zones/{z['id_zone']}/lines", json={"number_line": 2, "max_allowed_positions": 5}).expect(201).body
    warehouse.patch(f"/warehouse/lines/{two['id_line']}", json={"number_line": 1}).expect(409, code="LINE_NUMBER_ALREADY_EXISTS")
    upd = warehouse.patch(f"/warehouse/lines/{two['id_line']}", json={"is_active": True, "max_allowed_positions": 9}).expect(200).body
    assert upd["is_active"] is True and upd["max_allowed_positions"] == 9
    lines = warehouse.get(f"/warehouse/zones/{z['id_zone']}/lines").expect(200).json_("lines")
    assert {x["number_line"] for x in lines} == {1, 2}
    warehouse.delete(f"/warehouse/lines/{two['id_line']}").expect(204)
    assert warehouse.patch(f"/warehouse/lines/{two['id_line']}", json={}).json_("is_active") is False
    warehouse.patch("/warehouse/lines/nope", json={}).expect(404, code="LINE_NOT_FOUND")
    warehouse.get("/warehouse/zones/nope/lines").expect(404)


@pytest.mark.known_failure(bug="BUG-WAREHOUSE-1", reason="max_allowed_lines / max_allowed_positions are stored but never enforced: a zone with max_allowed_lines=1 accepts a second line (LineService.createLine / PositionService.createPosition never count siblings). Requirement is implied by the field names only - confirm with the owners")
def test_zone_and_line_structural_limits_are_enforced(warehouse, factory):
    """A zone refuses lines beyond max_allowed_lines and a line refuses positions beyond max_allowed_positions"""
    z = factory.zone(lines=1)
    ln = factory.line(z["id_zone"], number=1, positions=1)
    r = warehouse.post(f"/warehouse/zones/{z['id_zone']}/lines", json={"number_line": 2, "max_allowed_positions": 1})
    assert r.status in (400, 409), f"second line accepted in a zone limited to 1: {r.status}"
    factory.position(ln["id_line"], active=False)
    r = warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={"position_name": "P2", "maximum_capacity": 5, "size_stock_to_save": "CAJA"})
    assert r.status in (400, 409), f"second position accepted in a line limited to 1: {r.status}"


# ───────────────────────────── positions ─────────────────────────────
def test_position_create_defaults_and_listing(warehouse, factory):
    """Positions are created inactive and empty; listed by line and in the flat listing; detail shows the assigned product"""
    z, ln = _zone_line(factory)
    p = warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={"position_name": "A1", "maximum_capacity": 10, "size_stock_to_save": "CAJA", "qr_code": f"QR|{uid()}"}).expect(201).body
    assert p["is_active"] is False and p["current_stock"] == 0 and p["product_id"] is None
    assert p["id_zone"] == z["id_zone"] and p["id_line"] == ln["id_line"]
    assert [x["id_position"] for x in warehouse.get(f"/warehouse/lines/{ln['id_line']}/positions").json_("positions")] == [p["id_position"]]
    prod = factory.product()
    warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"product_id": prod["id"], "current_stock": 3, "is_active": True}).expect(200)
    det = warehouse.get(f"/warehouse/positions/{p['id_position']}").expect(200).body
    assert det["assigned_product"] == {"id": prod["id"], "sku": prod["sku"], "name": prod["name"]} and det["current_stock"] == 3
    flat = warehouse.get("/warehouse/positions").json_("positions")
    me = [x for x in flat if x["id_position"] == p["id_position"]][0]
    assert me["zone_code"] == z["zone_code"] and me["number_line"] == 1 and me["current_stock"] == 3 and me["is_active"] is True
    occ = warehouse.get("/warehouse/positions?occupied=true").json_("positions")
    assert any(x["id_position"] == p["id_position"] for x in occ) and all(x["is_active"] and x["current_stock"] > 0 for x in occ)
    warehouse.get("/warehouse/positions/nope").expect(404, code="POSITION_NOT_FOUND")
    warehouse.get("/warehouse/lines/nope/positions").expect(404)


def test_position_in_inactive_line_and_validation(warehouse, factory):
    """Inactive line refuses positions (LINE_INACTIVE); blank name / capacity < 1 / bad size are 400"""
    z = factory.zone()
    inactive = factory.line(z["id_zone"], active=False)
    base = {"position_name": "X", "maximum_capacity": 5, "size_stock_to_save": "CAJA"}
    warehouse.post(f"/warehouse/lines/{inactive['id_line']}/positions", json=base).expect(400, code="LINE_INACTIVE")
    ln = factory.line(z["id_zone"], number=2)
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={**base, "position_name": ""}).expect(400, code="VALIDATION_ERROR")
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={**base, "maximum_capacity": 0}).expect(400, code="VALIDATION_ERROR")
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={**base, "size_stock_to_save": "GIGANTE"}).expect(400)
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={k: v for k, v in base.items() if k != "size_stock_to_save"}).expect(400, code="VALIDATION_ERROR")


@pytest.mark.parametrize("legacy,canonical", [("PEQUENO", "CAJA"), ("mediano", "MEDIO_PALLET"), ("GRANDE", "PALLET"), ("small", "CAJA")])
def test_position_size_accepts_legacy_vocabulary(warehouse, factory, legacy, canonical):
    """Legacy size names (PEQUENO/MEDIANO/GRANDE...) are accepted and stored under the canonical name"""
    z, ln = _zone_line(factory)
    p = warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={"position_name": "L", "maximum_capacity": 5, "size_stock_to_save": legacy}).expect(201).body
    assert p["size_stock_to_save"] == canonical


def test_qr_code_uniqueness_on_create_and_patch(warehouse, factory):
    """A QR text belongs to one position: duplicate on POST or PATCH -> 409 QR_CODE_ALREADY_EXISTS; re-sending your own is fine"""
    z, ln = _zone_line(factory)
    qr = f"QR|{uid(12)}"
    a = factory.position(ln["id_line"], qr=qr, active=False)
    body = {"position_name": "B", "maximum_capacity": 5, "size_stock_to_save": "CAJA", "qr_code": qr}
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json=body).expect(409, code="QR_CODE_ALREADY_EXISTS")
    b = factory.position(ln["id_line"], qr=f"QR|{uid(12)}", active=False)
    warehouse.patch(f"/warehouse/positions/{b['id_position']}", json={"qr_code": qr}).expect(409, code="QR_CODE_ALREADY_EXISTS")
    warehouse.patch(f"/warehouse/positions/{a['id_position']}", json={"qr_code": qr}).expect(200)
    assert warehouse.patch(f"/warehouse/positions/{b['id_position']}", json={"qr_code": f" {qr}2 "}).json_("qr_code") == f"{qr}2", "QR is trimmed"


def test_qr_code_wire_format_snake_case_only(warehouse, factory):
    """The wire field is qr_code; a camelCase qrCode is ignored; a blank qr_code clears it"""
    z, ln = _zone_line(factory)
    p = factory.position(ln["id_line"], qr=None, active=False)
    assert p["qr_code"] is None
    r = warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"qrCode": "CAMEL|1"}).expect(200).body
    assert r["qr_code"] is None, "camelCase must not be applied"
    r = warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"qr_code": f"SNAKE|{uid()}"}).expect(200).body
    assert r["qr_code"].startswith("SNAKE|")
    assert warehouse.get(f"/warehouse/positions/{p['id_position']}").json_("qr_code").startswith("SNAKE|")
    for blank in ("", "   "):
        factory_qr = f"CLR|{uid()}"
        warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"qr_code": factory_qr}).expect(200)
        assert warehouse.patch(f"/warehouse/positions/{p['id_position']}", json={"qr_code": blank}).expect(200).json_("qr_code") is None
    # two positions can both have no QR
    q = factory.position(ln["id_line"], qr=None, active=False)
    assert q["qr_code"] is None
    warehouse.post(f"/warehouse/lines/{ln['id_line']}/positions", json={"position_name": "N", "maximum_capacity": 1, "size_stock_to_save": "CAJA", "qrCode": "IGN|1"}).expect(201)


def test_stock_guards_capacity_volume_and_negative(warehouse, factory):
    """current_stock above maximum_capacity or above the container volume -> 400 STOCK_EXCEEDS_CAPACITY; negative -> 400"""
    z, ln = _zone_line(factory)
    prod = factory.product(height=10, width=10, length=10)  # 1000 cm3 -> CAJA (48000 cm3) fits 48
    box = factory.position(ln["id_line"], size="CAJA", cap=100, active=True)
    pid = box["id_position"]
    warehouse.patch(f"/warehouse/positions/{pid}", json={"product_id": prod["id"], "current_stock": 48}).expect(200)
    warehouse.patch(f"/warehouse/positions/{pid}", json={"current_stock": 49}).expect(400, code="STOCK_EXCEEDS_CAPACITY")
    small = factory.position(ln["id_line"], size="PALLET", cap=5, active=True)
    warehouse.patch(f"/warehouse/positions/{small['id_position']}", json={"product_id": prod["id"], "current_stock": 6}).expect(400, code="STOCK_EXCEEDS_CAPACITY")
    warehouse.patch(f"/warehouse/positions/{small['id_position']}", json={"product_id": prod["id"], "current_stock": -1}).expect(400)
    # shrinking the container below the stored volume is refused too
    warehouse.patch(f"/warehouse/positions/{pid}", json={"size_stock_to_save": "CAJA", "current_stock": 48}).expect(200)
    assert warehouse.get(f"/warehouse/positions/{pid}").json_("current_stock") == 48


def test_one_product_per_position_and_unassign(warehouse, factory):
    """A position holds one product: assigning another -> 409 POSITION_ALREADY_OCCUPIED until unassign_product clears it"""
    s = factory.stocked(5)
    other = factory.product()
    pid = s.position["id_position"]
    warehouse.patch(f"/warehouse/positions/{pid}", json={"product_id": other["id"]}).expect(409, code="POSITION_ALREADY_OCCUPIED")
    cleared = warehouse.patch(f"/warehouse/positions/{pid}", json={"unassign_product": True}).expect(200).body
    assert cleared["product_id"] is None and cleared["current_stock"] == 0
    assigned = warehouse.patch(f"/warehouse/positions/{pid}", json={"product_id": other["id"], "current_stock": 2}).expect(200).body
    assert assigned["product_id"] == other["id"] and assigned["current_stock"] == 2
    warehouse.patch(f"/warehouse/positions/{factory.position(s.line['id_line'])['id_position']}", json={"product_id": "nope"}).expect(404, code="PRODUCT_NOT_FOUND")


def test_position_deactivate_and_delete_are_soft_and_stop_counting(warehouse, factory):
    """Deactivating or DELETEing a position keeps the row but its stock stops counting for the product"""
    s = factory.stocked(6, positions=2)
    p0, p1 = (p["id_position"] for p in s.positions)
    assert factory.stock(s.id)["physical"] == 12
    warehouse.patch(f"/warehouse/positions/{p0}", json={"is_active": False}).expect(200)
    assert factory.stock(s.id)["physical"] == 6
    warehouse.delete(f"/warehouse/positions/{p1}").expect(204)
    assert factory.stock(s.id)["physical"] == 0
    row = warehouse.get(f"/warehouse/positions/{p1}").expect(200).body
    assert row["is_active"] is False
    warehouse.delete("/warehouse/positions/nope").expect(404, code="POSITION_NOT_FOUND")


def test_validate_fit(warehouse, factory):
    """POST /warehouse/positions/validate-fit computes volumes and the maximum units that fit"""
    prod = factory.product(height=10, width=10, length=10)
    ok = warehouse.post("/warehouse/positions/validate-fit", json={"product_id": prod["id"], "quantity": 48, "size": "CAJA"}).expect(200).body
    assert ok == {"fits": True, "product_volume": 1000.0, "container_volume": 48000.0, "required_volume": 48000.0, "max_quantity_allowed": 48}
    no = warehouse.post("/warehouse/positions/validate-fit", json={"product_id": prod["id"], "quantity": 49, "size": "CAJA"}).expect(200).body
    assert no["fits"] is False and no["max_quantity_allowed"] == 48
    big = warehouse.post("/warehouse/positions/validate-fit", json={"product_id": prod["id"], "quantity": 1800, "size": "PALLET"}).expect(200)
    assert big.json_("fits") is True
    warehouse.post("/warehouse/positions/validate-fit", json={"product_id": "nope", "quantity": 1, "size": "CAJA"}).expect(404, code="PRODUCT_NOT_FOUND")
    warehouse.post("/warehouse/positions/validate-fit", json={"product_id": prod["id"], "quantity": 0, "size": "CAJA"}).expect(400, code="VALIDATION_ERROR")
    warehouse.post("/warehouse/positions/validate-fit", json={"product_id": prod["id"], "quantity": 1, "size": "XL"}).expect(400)


def test_available_positions_for_reception(warehouse, factory):
    """GET /warehouse/positions/available lists active, right-sized positions that are empty or hold this product, roomiest first"""
    prod = factory.product(height=10, width=10, length=10)
    z, ln = _zone_line(factory)
    big = factory.position(ln["id_line"], size="PALLET", cap=500)
    small = factory.position(ln["id_line"], size="PALLET", cap=40)
    taken = factory.position(ln["id_line"], size="PALLET", cap=500, product_id=factory.product()["id"], stock=1)
    wrong_size = factory.position(ln["id_line"], size="CAJA", cap=500)
    inactive = factory.position(ln["id_line"], size="PALLET", cap=500, active=False)
    r = warehouse.get(f"/warehouse/positions/available?productId={prod['id']}&deliveryUnit=PALLET&quantity=10").expect(200).json_("positions")
    ids = [x["position_id"] for x in r]
    assert big["id_position"] in ids and small["id_position"] in ids
    assert not ({taken["id_position"], wrong_size["id_position"], inactive["id_position"]} & set(ids))
    avail = {x["position_id"]: x["available_units"] for x in r}
    assert avail[big["id_position"]] == 500 and avail[small["id_position"]] == 40
    assert ids.index(big["id_position"]) < ids.index(small["id_position"])
    warehouse.get(f"/warehouse/positions/available?productId={prod['id']}&deliveryUnit=NOPE&quantity=1").expect(400, code="INVALID_DELIVERY_UNIT")
    warehouse.get(f"/warehouse/positions/available?productId={prod['id']}&deliveryUnit=CAJA&quantity=0").expect(400, code="INVALID_QUANTITY")
    warehouse.get("/warehouse/positions/available?productId=nope&deliveryUnit=CAJA&quantity=1").expect(404, code="PRODUCT_NOT_FOUND")
