"""Endpoints and credentials of the compose stack, all injected by e2e/suite.sh."""
import os

BACKEND = os.environ.get("BACKEND_URL", "http://backend:8080")
CONSOLE = os.environ.get("CONSOLE_URL", "http://console")
SUGGESTIONS = os.environ.get("SUGGESTIONS_URL", "http://suggestions:8080")
BRIDGE_HEALTH = os.environ.get("BRIDGE_URL", "http://bridge:8081")
RABBIT_API = os.environ.get("RABBIT_API", "http://rabbitmq:15672")
RABBIT_AUTH = ("suite", "suite")
KAFKA = os.environ.get("KAFKA_BROKERS", "redpanda:9092")
REDIS_HOST = os.environ.get("REDIS_HOST", "suggestions-redis")
MINIO = os.environ.get("MINIO_URL", "http://minio:9000")
VICTORIAMETRICS = os.environ.get("VM_URL", "http://victoriametrics:8428")
VICTORIALOGS = os.environ.get("VLOGS_URL", "http://victorialogs:9428")

PROJECT = os.environ.get("COMPOSE_PROJECT", "whsuite")
JWT_SECRET = os.environ["JWT_SECRET"]
INFRA_API_KEY = os.environ["INFRA_API_KEY"]
ADMIN_EMAIL = os.environ["ADMIN_EMAIL"]
ADMIN_PASSWORD = os.environ["ADMIN_PASSWORD"]

DEMO_PASSWORD = "Demo1234!"
# role -> demo account (docs/demo-credentials.md); SUPERADMIN uses the generated admin password.
ACCOUNTS = {
    "SUPERADMIN": (ADMIN_EMAIL, ADMIN_PASSWORD),
    "ADMIN_SYSTEM": ("system@smartwarehouse.local", DEMO_PASSWORD),
    "ADMIN_WAREHOUSE": ("warehouse@smartwarehouse.local", DEMO_PASSWORD),
    "ADMIN_SALES": ("sales@smartwarehouse.local", DEMO_PASSWORD),
    "PROVIDER": ("provider1@smartwarehouse.local", DEMO_PASSWORD),
    "DISPATCHER": ("dispatcher1@smartwarehouse.local", DEMO_PASSWORD),
    "OPERATOR": ("operator1@smartwarehouse.local", DEMO_PASSWORD),
}
ROLES = ["SUPERADMIN", "ADMIN_SYSTEM", "ADMIN_WAREHOUSE", "ADMIN_SALES", "PROVIDER", "DISPATCHER", "OPERATOR", "DASHBOARD"]

# Namespace the backend uses to turn Infra's vehicle ids into its own (VehicleIds.java).
VEHICLE_NAMESPACE = "6f0a5d3e-2b7c-4c1e-9a58-3d1f7b2e4a90"
