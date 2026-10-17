"""Rover + camera simulator for one vehicle, speaking the device side of Infra's PROTOCOLO.md over MQTT.

Test tooling for e2e/run.sh real: it stands in for the ESP8266 rover and the Raspberry Pi camera so Infra's
real central (orquestador) can be driven without hardware.

  - announces rover and camera online (Last Will = offline) and answers telemetry_request
  - on camera set_target {qr, leg_id}: after DETECT_DELAY seconds publishes camera/event qr_detected,
    unless the QR contains "MISS" (simulates a code the camera never finds)
  - control topic sim/ctl: "rover_down" / "rover_up" drops / restores the rover (for CONNECTION_LOST)
"""
import json
import os
import threading
import time

import paho.mqtt.client as mqtt

HOST = os.environ.get("MQTT_HOST", "rabbitmq")
USER = os.environ.get("MQTT_USER", "admin_admin")
PASSWORD = os.environ.get("MQTT_PASSWORD", "admin")
VID = os.environ["VEHICLE_ID"]
DETECT_DELAY = float(os.environ.get("DETECT_DELAY", "1.5"))
VOLTAGE = float(os.environ.get("BATTERY_VOLTAGE", "2.87"))
STARTED = time.time()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def make(part):
    c = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"sim-{part}-{VID}", protocol=mqtt.MQTTv311, clean_session=True)
    c.username_pw_set(USER, PASSWORD)
    c.will_set(f"devices/{VID}/{part}/status", json.dumps({"online": False}), qos=1)
    return c


rover, camera = make("rover"), make("camera")
motion = {"v": "stopped"}


def pub(c, part, kind, payload, qos=0):
    c.publish(f"devices/{VID}/{part}/{kind}", json.dumps(payload), qos=qos)


def rover_telemetry():
    pub(rover, "rover", "telemetry", {"vehicle_id": VID, "battery_voltage": VOLTAGE, "motion": motion["v"], "rssi": -61, "uptime_s": int(time.time() - STARTED)})


def camera_telemetry():
    pub(camera, "camera", "telemetry", {"vehicle_id": VID, "fps": 24, "uptime_s": int(time.time() - STARTED)})


def on_rover_connect(c, *_):
    c.subscribe([(f"devices/{VID}/rover/cmd", 1), ("devices/broadcast/telemetry_request", 0), ("sim/ctl", 1)])
    pub(c, "rover", "status", {"online": True}, qos=1)
    rover_telemetry()
    log("rover online")


def on_camera_connect(c, *_):
    c.subscribe([(f"devices/{VID}/camera/cmd", 1), ("devices/broadcast/telemetry_request", 0)])
    pub(c, "camera", "status", {"online": True}, qos=1)
    camera_telemetry()
    log("camera online")


def detect_later(qr, leg_id):
    time.sleep(DETECT_DELAY)
    pub(camera, "camera", "event", {"event": "qr_detected", "qr": qr, "leg_id": leg_id, "timestamp": time.time()}, qos=1)
    log("qr_detected", qr, leg_id)


def on_rover_message(c, _u, msg):
    body = msg.payload.decode()
    if msg.topic == "sim/ctl":
        if body == "rover_down" and rover.is_connected():
            pub(rover, "rover", "status", {"online": False}, qos=1)
            time.sleep(0.3)
            rover.disconnect()
            log("rover DOWN")
        elif body == "rover_up" and not rover.is_connected():
            rover.reconnect()
            log("rover UP")
        return
    if msg.topic.endswith("telemetry_request"):
        rover_telemetry()
        return
    cmd = json.loads(body).get("cmd")
    if cmd in ("forward", "backward"):
        motion["v"] = cmd
    elif cmd == "stop":
        motion["v"] = "stopped"
    log("rover cmd", cmd)


def on_camera_message(c, _u, msg):
    if msg.topic.endswith("telemetry_request"):
        camera_telemetry()
        return
    data = json.loads(msg.payload.decode())
    log("camera cmd", data)
    if data.get("cmd") == "set_target":
        qr = data["qr"]
        if "MISS" in qr:
            log("camera will never find", qr)
        else:
            threading.Thread(target=detect_later, args=(qr, data.get("leg_id")), daemon=True).start()


rover.on_connect, rover.on_message = on_rover_connect, on_rover_message
camera.on_connect, camera.on_message = on_camera_connect, on_camera_message
for c in (rover, camera):
    c.connect_async(HOST, 1883, keepalive=10)
    c.loop_start()
log("simulating vehicle", VID)
while True:
    time.sleep(3600)
