"""Handles on the infrastructure around the backend: Kafka, RabbitMQ, Docker, Redis."""
import json
import threading
import time
import uuid
from typing import Any, Callable

import docker
import httpx
import redis as redis_lib
from confluent_kafka import Consumer

from . import config
from .api import wait_until


class KafkaTap:
    """Reads every message of the given topics from the beginning in a background thread, so a test
    can look for 'the message for my order' whenever it happens, however long ago it was produced."""

    def __init__(self, topics=("orders", "order.completed")):
        self.topics = list(topics)
        self.messages: list[dict] = []
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._consumer = Consumer({
            "bootstrap.servers": config.KAFKA,
            "group.id": f"e2e-tap-{uuid.uuid4().hex[:8]}",
            "auto.offset.reset": "earliest",
            "enable.auto.commit": False,
            "allow.auto.create.topics": False,
            "topic.metadata.refresh.interval.ms": 1000,
            "session.timeout.ms": 10000,
        })
        self._consumer.subscribe(self.topics)
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        while not self._stop.is_set():
            try:
                m = self._consumer.poll(0.5)
            except Exception:  # broker restarts (resilience group): keep polling
                time.sleep(1)
                continue
            if m is None or m.error():
                continue
            raw = m.value() or b""
            try:
                value = json.loads(raw)
            except ValueError:
                value = None
            with self._lock:
                self.messages.append({"topic": m.topic(), "key": (m.key() or b"").decode(errors="replace"),
                                      "value": value, "raw": raw.decode(errors="replace"), "ts": time.time()})

    def find(self, topic: str, pred: Callable[[dict], bool]) -> list[dict]:
        with self._lock:
            return [m for m in self.messages if m["topic"] == topic and isinstance(m["value"], dict) and pred(m["value"])]

    def for_order(self, order_id: str, status: str | None = None, id_key: str = "order_id", topic: str = "orders") -> list[dict]:
        return self.find(topic, lambda v: v.get(id_key) == order_id and (status is None or v.get("status") == status))

    def wait_order(self, order_id: str, status: str | None = None, timeout: float = 30, topic: str = "orders",
                   id_key: str = "order_id") -> dict:
        found = wait_until(lambda: self.for_order(order_id, status, id_key, topic), timeout=timeout,
                           desc=f"{topic} message for order {order_id} status={status}")
        return found[0]

    def close(self):
        self._stop.set()
        self._thread.join(timeout=5)
        self._consumer.close()


class Rabbit:
    """RabbitMQ through its management API. Owns a sink queue bound like Infra's own subscription."""

    SINK = "e2e.orders.sink"

    def __init__(self):
        self.http = httpx.Client(base_url=config.RABBIT_API, auth=config.RABBIT_AUTH, timeout=20)
        self.seen: list[dict] = []

    def ensure_sink(self):
        self.http.put(f"/api/queues/%2F/{self.SINK}", json={"durable": True}).raise_for_status()
        self.http.post(f"/api/bindings/%2F/e/amq.topic/q/{self.SINK}", json={"routing_key": "orders"}).raise_for_status()

    def drain_sink(self) -> list[dict]:
        """Move whatever the sink holds into self.seen (decoded JSON bodies with their properties)."""
        while True:
            r = self.http.post(f"/api/queues/%2F/{self.SINK}/get",
                               json={"count": 200, "ackmode": "ack_requeue_false", "encoding": "auto"})
            r.raise_for_status()
            batch = r.json()
            for m in batch:
                try:
                    body = json.loads(m["payload"])
                except ValueError:
                    body = None
                self.seen.append({"body": body, "raw": m["payload"], "props": m.get("properties", {}),
                                  "routing_key": m.get("routing_key"), "exchange": m.get("exchange")})
            if not batch:
                return self.seen

    def wait_order(self, order_id: str, status: str | None = None, timeout: float = 30) -> dict:
        def look():
            self.drain_sink()
            for m in self.seen:
                b = m["body"]
                if isinstance(b, dict) and b.get("order_id") == order_id and (status is None or b.get("status") == status):
                    return m
            return None
        return wait_until(look, timeout=timeout, desc=f"AMQP message for order {order_id} status={status}")

    def publish(self, routing_key: str, payload: dict | str, exchange: str = "amq.topic") -> bool:
        body = payload if isinstance(payload, str) else json.dumps(payload)
        r = self.http.post(f"/api/exchanges/%2F/{exchange}/publish", json={
            "properties": {"delivery_mode": 2, "content_type": "application/json"},
            "routing_key": routing_key, "payload": body, "payload_encoding": "string"})
        r.raise_for_status()
        return r.json().get("routed", False)

    def queue(self, name: str) -> dict | None:
        r = self.http.get(f"/api/queues/%2F/{name}")
        return r.json() if r.status_code == 200 else None

    def queue_depth(self, name: str) -> int:
        q = self.queue(name)
        return (q or {}).get("messages", 0)

    def purge(self, name: str):
        self.http.delete(f"/api/queues/%2F/{name}/contents")

    def exists(self, name: str) -> bool:
        return self.queue(name) is not None

    def delete_queue(self, name: str):
        self.http.delete(f"/api/queues/%2F/{name}")


class Stack:
    """Controls the compose containers of this project through the Docker socket."""

    def __init__(self):
        self.client = docker.from_env()

    def container(self, service: str):
        found = self.client.containers.list(all=True, filters={
            "label": [f"com.docker.compose.project={config.PROJECT}", f"com.docker.compose.service={service}"]})
        if not found:
            raise AssertionError(f"no container for compose service {service!r} in project {config.PROJECT}")
        return found[0]

    def status(self, service: str) -> str:
        c = self.container(service)
        c.reload()
        return c.status

    def health(self, service: str) -> str | None:
        c = self.container(service)
        c.reload()
        return c.attrs["State"].get("Health", {}).get("Status")

    def stop(self, service: str, timeout: int = 20):
        self.container(service).stop(timeout=timeout)

    def start(self, service: str):
        self.container(service).start()

    def restart(self, service: str, timeout: int = 20):
        self.container(service).restart(timeout=timeout)

    def kill(self, service: str):
        self.container(service).kill()

    def wait_healthy(self, service: str, timeout: float = 240):
        def ok():
            c = self.container(service)
            c.reload()
            st = c.attrs["State"]
            h = st.get("Health", {}).get("Status")
            return st["Running"] and h in (None, "healthy")
        wait_until(ok, timeout=timeout, interval=2, desc=f"container {service} running/healthy")

    def logs(self, service: str, since: float | None = None) -> str:
        kw: dict[str, Any] = {}
        if since:
            kw["since"] = int(since)
        return self.container(service).logs(**kw).decode(errors="replace")

    def exec(self, service: str, cmd: list[str]) -> tuple[int, str]:
        code, out = self.container(service).exec_run(cmd)
        return code, out.decode(errors="replace")


def redis_client() -> redis_lib.Redis:
    return redis_lib.Redis(host=config.REDIS_HOST, port=6379, decode_responses=True, socket_timeout=5)
