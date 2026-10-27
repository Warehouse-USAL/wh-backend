"""WebSocket helpers (sync client of the `websockets` package)."""
import json
import time
from contextlib import contextmanager
from typing import Callable

from websockets.exceptions import ConnectionClosed, InvalidStatus
from websockets.sync.client import connect

from . import config

WS_BASE = config.BACKEND.replace("http://", "ws://").replace("https://", "wss://")


class Feed:
    """An open feed; `.events` collects every JSON message received so far (call .pump())."""

    def __init__(self, ws):
        self.ws = ws
        self.events: list[dict] = []

    def pump(self, seconds: float = 0.2):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                msg = self.ws.recv(timeout=max(0.05, end - time.monotonic()))
            except TimeoutError:
                return
            except ConnectionClosed:
                return
            try:
                self.events.append(json.loads(msg))
            except ValueError:
                self.events.append({"raw": msg})

    def wait(self, pred: Callable[[dict], bool], timeout: float = 20) -> dict:
        deadline = time.monotonic() + timeout
        seen = 0
        while True:
            for ev in self.events[seen:]:
                if pred(ev):
                    return ev
            seen = len(self.events)
            if time.monotonic() > deadline:
                raise AssertionError(f"no matching websocket event within {timeout}s; got {json.dumps(self.events, default=str)[:1500]}")
            self.pump(0.3)


@contextmanager
def feed(path: str, token: str | None):
    url = f"{WS_BASE}{path}" + (f"?token={token}" if token is not None else "")
    with connect(url, open_timeout=10, max_size=2 ** 22) as ws:
        yield Feed(ws)


def handshake_status(path: str, token: str | None) -> int:
    """HTTP status of the upgrade attempt (101 when accepted)."""
    url = f"{WS_BASE}{path}" + (f"?token={token}" if token is not None else "")
    try:
        with connect(url, open_timeout=10):
            return 101
    except InvalidStatus as e:
        return e.response.status_code
