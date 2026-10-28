"""Builders for isolated test data. Every call makes new, uniquely named records, so tests never
share state and can run in any order or twice."""
import uuid
from types import SimpleNamespace

from . import config
from .api import Api, Resp

ADDRESS = {"street": "1 Test St", "department": "QA", "floor": "1", "postal_code": "00001"}


def uid(n: int = 8) -> str:
    return uuid.uuid4().hex[:n]


class InfraClient:
    """Plays Infra's side of the HTTP contract (what the bridge does after reading RabbitMQ)."""

    def __init__(self, api: Api):
        self.api = api

    def status(self, order_id: str, status: str, vehicle_id: str | None = None, key: str | None = config.INFRA_API_KEY) -> Resp:
        body = {"status": status, "timestamp": "2026-10-27T12:00:00Z"}
        if vehicle_id:
            body["vehicle_id"] = vehicle_id
        return self.api.post(f"/infra/orders/{order_id}/status", token=None, json=body,
                             headers={"X-Api-Key": key} if key else {})

    def telemetry(self, infra_vehicle_id: str, status: str = "idle", battery: int = 80, error: dict | None = None,
                  key: str | None = config.INFRA_API_KEY) -> Resp:
        body = {"status": status, "battery": battery, "error": error, "timestamp": "2026-10-27T12:00:00Z"}
        return self.api.post(f"/infra/vehicles/{infra_vehicle_id}/telemetry", token=None, json=body,
                             headers={"X-Api-Key": key} if key else {})

    @staticmethod
    def backend_vehicle_id(infra_id: str) -> str:
        return str(uuid.uuid5(uuid.UUID(config.VEHICLE_NAMESPACE), infra_id))


class Factory:
    def __init__(self, wh: Api, sales: Api):
        self.wh, self.sales = wh, sales
        self.infra = InfraClient(Api())

    # ── catalog ──
    def product(self, **over) -> dict:
        body = {"sku": f"E2E-{uid(10).upper()}", "name": f"E2E product {uid(4)}", "category": "OTROS",
                "description": "created by the e2e suite", "height": 10, "width": 10, "length": 10, "weight": 1,
                "max_quantity_per_order": 50, "minimum_stock": 0,
                "price": {"amount_cents": 1999, "currency": "USD", "tax_included": True}}
        body.update(over)
        return self.wh.post("/products", json=body).expect(201).json_("product")

    # ── warehouse ──
    def zone(self, lines: int = 5, active: bool = True, code: str | None = None) -> dict:
        z = self.wh.post("/warehouse/zones", json={"zone_code": code or f"Z{uid(6).upper()}", "max_allowed_lines": lines}).expect(201).body
        if active:
            z = self.wh.patch(f"/warehouse/zones/{z['id_zone']}", json={"is_active": True}).expect(200).body
        return z

    def line(self, zone_id: str, number: int = 1, positions: int = 20, active: bool = True) -> dict:
        ln = self.wh.post(f"/warehouse/zones/{zone_id}/lines", json={"number_line": number, "max_allowed_positions": positions}).expect(201).body
        if active:
            ln = self.wh.patch(f"/warehouse/lines/{ln['id_line']}", json={"is_active": True}).expect(200).body
        return ln

    def position(self, line_id: str, name: str | None = None, qr: str | None = "auto", cap: int = 500,
                 size: str = "PALLET", product_id: str | None = None, stock: int = 0, active: bool = True) -> dict:
        body = {"position_name": name or f"P{uid(5)}", "maximum_capacity": cap, "size_stock_to_save": size}
        if qr == "auto":
            qr = f"E2E|{uid(12)}"
        if qr is not None:
            body["qr_code"] = qr
        p = self.wh.post(f"/warehouse/lines/{line_id}/positions", json=body).expect(201).body
        patch: dict = {}
        if product_id:
            patch["product_id"] = product_id
        if stock:
            patch["current_stock"] = stock
        if active:
            patch["is_active"] = True
        if patch:
            p = self.wh.patch(f"/warehouse/positions/{p['id_position']}", json=patch).expect(200).body
        return p

    def stocked(self, qty: int = 20, *, positions: int = 1, product: dict | None = None, **product_over) -> SimpleNamespace:
        """A product with `qty` units in each of `positions` fresh positions (own zone and line)."""
        prod = product or self.product(**product_over)
        zone = self.zone()
        line = self.line(zone["id_zone"])
        poss = [self.position(line["id_line"], name=f"P{i+1:02d}", product_id=prod["id"], stock=qty) for i in range(positions)]
        return SimpleNamespace(product=prod, zone=zone, line=line, positions=poss, position=poss[0],
                               qrs=[p["qr_code"] for p in poss], id=prod["id"])

    # ── orders ──
    def order_body(self, items: list[tuple[str, int]], **over) -> dict:
        body = {"items": [{"product_id": p, "quantity": q} for p, q in items], "destination_area": "E2E-AREA",
                "address": dict(ADDRESS)}
        body.update(over)
        return body

    def order(self, api: Api, items: list[tuple[str, int]], key: str | None = None, **over) -> dict:
        headers = {"Idempotency-Key": key} if key else {}
        return api.post("/orders", json=self.order_body(items, **over), headers=headers).expect(201).json_("order")

    def stock(self, product_id: str) -> dict:
        return self.wh.get(f"/products/{product_id}").expect(200).json_("product", "stock")

    def get_order(self, order_id: str) -> dict:
        return self.wh.get(f"/orders/{order_id}").expect(200).json_("order")

    def user(self, admin: Api, role: str, password: str = "Passw0rd!x1", **over) -> SimpleNamespace:
        email = f"e2e-{uid(10)}@e2e.test"
        body = {"email": email, "name": f"E2E {role} {uid(4)}", "role": role, "initial_password": password}
        body.update(over)
        created = admin.post("/users", json=body).expect(201).body
        created = created.get("user", created)
        return SimpleNamespace(id=created["id"], email=email, password=password, role=role, data=created)
