"""Area: vehicles and telemetry (registration, admin patch, Infra telemetry creating/updating vehicles)."""
import uuid

import pytest

from lib import config
from lib.factory import InfraClient, uid


def test_register_list_and_get_vehicle(system, warehouse, factory):
    """POST /vehicles registers an OFFLINE vehicle; it is listed (paginated) and readable by id; WAREHOUSE can read but not register"""
    name = f"Rover-{uid(6)}"
    v = system.post("/vehicles", json={"name": name}).expect(201).body
    assert v["name"] == name and v["status"] == "offline" and v["current_order_id"] is None and v["battery"] == 0
    assert set(v) == {"id", "name", "status", "position", "battery", "current_order_id", "last_seen_at", "operation_since"}
    assert warehouse.get(f"/vehicles/{v['id']}").expect(200).json_("name") == name
    page = warehouse.get("/vehicles?size=2").expect(200)
    assert len(page.json_("vehicles")) <= 2 and page.json_("pagination", "total_elements") >= 6
    warehouse.post("/vehicles", json={"name": "nope"}).expect(403)
    warehouse.get("/vehicles/nope").expect(404, code="VEHICLE_NOT_FOUND")


def test_patch_vehicle_state(system, warehouse):
    """PATCH /internal/vehicles/{id}: partial update of status/position/battery; unknown id 404; bad status 400; WAREHOUSE 403"""
    v = system.post("/vehicles", json={"name": f"Rover-{uid(6)}"}).expect(201).body
    r = system.patch(f"/internal/vehicles/{v['id']}", json={"status": "IDLE", "battery": 77, "position_x": 3.5, "position_y": 9.25}).expect(200).body
    assert (r["status"], r["battery"], r["position"]) == ("idle", 77, {"x": 3.5, "y": 9.25})
    r2 = system.patch(f"/internal/vehicles/{v['id']}", json={"battery": 70}).expect(200).body
    assert r2["status"] == "idle" and r2["position"] == {"x": 3.5, "y": 9.25} and r2["battery"] == 70, "untouched fields stay"
    assert system.patch(f"/internal/vehicles/{v['id']}", json={}).expect(200).json_("battery") == 70
    system.patch(f"/internal/vehicles/{v['id']}", json={"status": "flying"}).expect(400)
    system.patch("/internal/vehicles/nope", json={"battery": 1}).expect(404, code="VEHICLE_NOT_FOUND")
    warehouse.patch(f"/internal/vehicles/{v['id']}", json={"battery": 1}).expect(403)


def test_battery_must_be_a_percentage(system, infra, vehicle_id):
    """A battery level outside 0..100 is refused or clamped, never stored as is"""
    v = system.post("/vehicles", json={"name": f"Rover-{uid(6)}"}).expect(201).body
    r = system.patch(f"/internal/vehicles/{v['id']}", json={"battery": 250})
    assert r.status == 400 or r.json_("battery") <= 100
    infra.telemetry(vehicle_id[0], "idle", -40)
    assert system.get(f"/vehicles/{vehicle_id[1]}").json_("battery") >= 0


def test_first_telemetry_creates_the_vehicle_with_a_derived_id(infra, warehouse, vehicle_id):
    """The first reading of an Infra id creates the vehicle under the deterministic UUIDv5; the response returns the backend id"""
    infra_vid, vid = vehicle_id
    warehouse.get(f"/vehicles/{vid}").expect(404)
    r = infra.telemetry(infra_vid, "idle", 88).expect(200)
    assert r.json_("vehicle_id") == vid and str(uuid.UUID(vid)) == vid
    v = warehouse.get(f"/vehicles/{vid}").expect(200).body
    assert v["status"] == "idle" and v["battery"] == 88 and v["last_seen_at"] and v["name"]
    assert infra.telemetry(infra_vid, "idle", 87).json_("vehicle_id") == vid, "same Infra id -> same vehicle"
    assert warehouse.get(f"/vehicles/{vid}").json_("battery") == 87


def test_telemetry_status_transitions_and_partial_readings(infra, warehouse, vehicle_id):
    """busy/idle/offline readings set the status; unknown status or missing battery keep the previous values instead of failing"""
    infra_vid, vid = vehicle_id
    infra.telemetry(infra_vid, "busy", 60).expect(200)
    assert warehouse.get(f"/vehicles/{vid}").json_("status") == "busy"
    infra.api.post(f"/infra/vehicles/{infra_vid}/telemetry", token=None, headers={"X-Api-Key": config.INFRA_API_KEY},
                   json={"status": "levitating", "battery": 55}).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "busy" and v["battery"] == 55, "unrecognised status ignored, battery applied"
    infra.api.post(f"/infra/vehicles/{infra_vid}/telemetry", token=None, headers={"X-Api-Key": config.INFRA_API_KEY}, json={"status": "idle"}).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "idle" and v["battery"] == 55, "null battery keeps the last known one"
    infra.telemetry(infra_vid, "offline", 55).expect(200)
    assert warehouse.get(f"/vehicles/{vid}").json_("status") == "offline"


def test_telemetry_fault_marks_the_vehicle_in_error(infra, warehouse, vehicle_id):
    """A reading with an error and no status puts the vehicle in ERROR; a following healthy reading restores it and restarts its operation window"""
    infra_vid, vid = vehicle_id
    infra.telemetry(infra_vid, "idle", 90).expect(200)
    assert warehouse.get(f"/vehicles/{vid}").json_("operation_since")
    infra.api.post(f"/infra/vehicles/{infra_vid}/telemetry", token=None, headers={"X-Api-Key": config.INFRA_API_KEY},
                   json={"battery": 80, "error": {"code": "MOTOR_STALL", "message": "motor stalled"}}).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "error" and v["operation_since"] is None
    infra.telemetry(infra_vid, "idle", 80).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "idle" and v["operation_since"], "coming back from ERROR starts a new operation window"


def test_telemetry_is_idempotent_for_the_same_reading(infra, warehouse, vehicle_id):
    """Redelivered readings (the bridge is at-least-once) leave the same state"""
    infra_vid, vid = vehicle_id
    for _ in range(3):
        infra.telemetry(infra_vid, "idle", 42).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert (v["status"], v["battery"]) == ("idle", 42)
    assert sum(1 for x in warehouse.get("/vehicles?size=50").json_("vehicles") if x["id"] == vid) in (0, 1)


def test_busy_vehicle_released_when_its_order_is_cancelled_by_infra(infra, operator, warehouse, factory, vehicle_id):
    """Vehicle/order coupling: assigned -> busy with current_order_id; Infra's cancelled -> idle again and free for a new order"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(5)
    a, b = factory.order(operator, [(s.id, 1)]), factory.order(operator, [(s.id, 1)])
    infra.status(a["id"], "in_progress", infra_vid).expect(200)
    assert warehouse.get(f"/vehicles/{vid}").json_("current_order_id") == a["id"]
    infra.status(a["id"], "cancelled", infra_vid).expect(200)
    v = warehouse.get(f"/vehicles/{vid}").body
    assert v["status"] == "idle" and v["current_order_id"] is None
    infra.status(b["id"], "in_progress", infra_vid).expect(200)
    assert warehouse.get(f"/orders?vehicleId={vid}").json_("orders")[0]["id"] in {a["id"], b["id"]}


def test_order_list_filter_by_assigned_vehicle(infra, operator, warehouse, factory, vehicle_id):
    """GET /orders?vehicleId=... returns exactly the orders assigned to that vehicle"""
    infra_vid, vid = vehicle_id
    s = factory.stocked(5)
    a = factory.order(operator, [(s.id, 1)])
    factory.order(operator, [(s.id, 1)])
    infra.status(a["id"], "in_progress", infra_vid).expect(200)
    ids = [o["id"] for o in warehouse.get(f"/orders?vehicleId={vid}").json_("orders")]
    assert ids == [a["id"]]
