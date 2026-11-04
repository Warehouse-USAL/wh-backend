"""Console SSE stream and WebSocket fan-out under many clients (asyncio, httpx + websockets only).

  python streams.py sse [clients]   open `clients` console streams (default 60) for SSE_SECONDS (20) and report
  python streams.py ws  [clients]   open `clients` /ws/v1/orders sockets (default 200), create ORDERS (30) orders
                                    and report delivery ratio and latency percentiles

Env: BACKEND_URL, ADMIN_EMAIL, ADMIN_PASSWORD. The console caps streams (10 per user, 50 in total, docs/CONSOLE.md),
so the SSE run spreads clients over several SUPERADMIN users and expects the surplus to be refused with 429.
"""
import asyncio
import json
import os
import statistics
import sys
import time
import uuid

import httpx
from websockets.asyncio.client import connect

BACKEND = os.environ.get("BACKEND_URL", "http://backend:8080")
WS_BASE = BACKEND.replace("http", "ws", 1)
ADMIN = (os.environ["ADMIN_EMAIL"], os.environ["ADMIN_PASSWORD"])
PASSWORD = "Demo1234!"


def pct(values, p):
    if not values:
        return None
    values = sorted(values)
    return round(values[min(len(values) - 1, int(len(values) * p / 100))], 1)


async def login(c, email, password):
    r = await c.post("/auth/login", json={"email": email, "password": password})
    r.raise_for_status()
    return r.json()["token"]


async def sse(clients: int):
    seconds = float(os.environ.get("SSE_SECONDS", "20"))
    async with httpx.AsyncClient(base_url=BACKEND, timeout=30) as c:
        admin = await login(c, *ADMIN)
        tokens = [admin]
        for i in range((clients + 6) // 7):  # 7 streams per user, under the per-user cap of 10
            email = f"load-sse-{uuid.uuid4().hex[:8]}@load.test"
            r = await c.post("/users", headers={"Authorization": f"Bearer {admin}"},
                             json={"email": email, "name": f"load {i}", "role": "SUPERADMIN", "initial_password": "Load-Passw0rd!x1"})
            if r.status_code == 201:
                tokens.append(await login(c, email, "Load-Passw0rd!x1"))
    res = {"accepted": 0, "refused_429": 0, "other": 0, "first_snapshot_ms": [], "events": 0, "evicted": 0}

    async def one(i):
        token = tokens[i % len(tokens)]
        body = {"subscriptions": [{"id": "st", "kind": "status"}, {"id": "w.orders", "kind": "ws-events", "feed": "orders"}]}
        t0 = time.monotonic()
        async with httpx.AsyncClient(base_url=BACKEND, timeout=httpx.Timeout(seconds + 15, connect=15)) as cl:
            try:
                async with cl.stream("POST", "/console/stream", json=body,
                                     headers={"Authorization": f"Bearer {token}", "Accept": "text/event-stream"}) as r:
                    if r.status_code == 429:
                        res["refused_429"] += 1
                        return
                    if r.status_code != 200:
                        res["other"] += 1
                        return
                    res["accepted"] += 1
                    first = True
                    async for line in r.aiter_lines():
                        if line.startswith("event:"):
                            res["events"] += 1
                            if first:
                                res["first_snapshot_ms"].append((time.monotonic() - t0) * 1000)
                                first = False
                            if "limit" in line:
                                res["evicted"] += 1
                        if time.monotonic() - t0 > seconds:
                            return
            except (httpx.HTTPError, OSError):
                res["other"] += 1

    await asyncio.gather(*(one(i) for i in range(clients)))
    snap = res.pop("first_snapshot_ms")
    print(json.dumps({"scenario": "console_sse", "clients": clients, "seconds": seconds, **res,
                      "first_snapshot_ms_p50": pct(snap, 50), "first_snapshot_ms_p95": pct(snap, 95), "first_snapshot_ms_max": pct(snap, 100)}))


async def ws(clients: int):
    n_orders = int(os.environ.get("ORDERS", "30"))
    async with httpx.AsyncClient(base_url=BACKEND, timeout=30) as c:
        admin = await login(c, *ADMIN)
        wh = await login(c, "warehouse@smartwarehouse.local", PASSWORD)
        op = await login(c, "operator1@smartwarehouse.local", PASSWORD)
        h = {"Authorization": f"Bearer {wh}"}
        sku = f"LOADWS-{uuid.uuid4().hex[:8].upper()}"
        p = (await c.post("/products", headers=h, json={"sku": sku, "name": sku, "category": "OTROS", "description": "load", "height": 1, "width": 1, "length": 1, "weight": 1,
                                                        "max_quantity_per_order": 50, "minimum_stock": 0, "price": {"amount_cents": 100, "currency": "USD", "tax_included": True}})).json()["product"]
        z = (await c.post("/warehouse/zones", headers=h, json={"zone_code": f"ZW{uuid.uuid4().hex[:6].upper()}", "max_allowed_lines": 5})).json()
        await c.patch(f"/warehouse/zones/{z['id_zone']}", headers=h, json={"is_active": True})
        ln = (await c.post(f"/warehouse/zones/{z['id_zone']}/lines", headers=h, json={"number_line": 1, "max_allowed_positions": 20})).json()
        await c.patch(f"/warehouse/lines/{ln['id_line']}", headers=h, json={"is_active": True})
        pos = (await c.post(f"/warehouse/lines/{ln['id_line']}/positions", headers=h, json={"position_name": "P01", "maximum_capacity": 500, "size_stock_to_save": "PALLET", "qr_code": f"LOADWS|{uuid.uuid4().hex[:10]}"})).json()
        await c.patch(f"/warehouse/positions/{pos['id_position']}", headers=h, json={"product_id": p["id"], "current_stock": 500, "is_active": True})

    sent: dict[str, float] = {}
    arrivals: dict[str, list] = {}
    latencies: list[float] = []
    received = [0] * clients
    connected = 0
    stop = asyncio.Event()

    async def client(i):
        nonlocal connected
        try:
            async with connect(f"{WS_BASE}/ws/v1/orders?token={admin}", open_timeout=20, max_size=2 ** 22) as sock:
                connected += 1
                while not stop.is_set():
                    try:
                        msg = await asyncio.wait_for(sock.recv(), timeout=1)
                    except asyncio.TimeoutError:
                        continue
                    ev = json.loads(msg)
                    oid = ev.get("payload", {}).get("id")
                    if oid:  # the event can beat the POST response: match it up afterwards
                        arrivals.setdefault(oid, []).append((i, time.monotonic()))
        except (OSError, Exception):  # noqa: BLE001 - a refused/closed socket is a result, not a crash
            return

    tasks = [asyncio.create_task(client(i)) for i in range(clients)]
    for _ in range(100):
        if connected >= clients:
            break
        await asyncio.sleep(0.2)
    await asyncio.sleep(1)
    body = {"items": [{"product_id": p["id"], "quantity": 1}], "destination_area": "LOAD",
            "address": {"street": "1 Load St", "department": "QA", "floor": "1", "postal_code": "00001"}}
    ids = []
    async with httpx.AsyncClient(base_url=BACKEND, timeout=30, headers={"Authorization": f"Bearer {op}"}) as c:
        for _ in range(n_orders):
            t0 = time.monotonic()
            r = await c.post("/orders", json=body)
            if r.status_code == 201:
                oid = r.json()["order"]["id"]
                sent[oid] = t0
                ids.append(oid)
            await asyncio.sleep(0.1)
    await asyncio.sleep(5)
    stop.set()
    await asyncio.gather(*tasks, return_exceptions=True)
    for oid, t0 in sent.items():
        for i, at in arrivals.get(oid, []):
            received[i] += 1
            latencies.append((at - t0) * 1000)
    expected = len(ids) * connected
    got = sum(received)
    print(json.dumps({"scenario": "ws_fanout", "clients_requested": clients, "clients_connected": connected, "orders": len(ids),
                      "events_expected": expected, "events_received": got, "delivery_ratio": round(got / expected, 4) if expected else None,
                      "latency_ms_p50": pct(latencies, 50), "latency_ms_p95": pct(latencies, 95), "latency_ms_p99": pct(latencies, 99),
                      "latency_ms_mean": round(statistics.mean(latencies), 1) if latencies else None}))


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "sse"
    count = int(sys.argv[2]) if len(sys.argv) > 2 else (60 if mode == "sse" else 200)
    asyncio.run(sse(count) if mode == "sse" else ws(count))
