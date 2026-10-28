"""Minimal Server-Sent-Events reader for the console stream (POST + Authorization header)."""
import json
import time
from typing import Callable

import httpx

from . import config


def read_sse(token: str, body: dict, until: Callable[[list[dict]], bool], timeout: float = 30,
             trigger: Callable[[], None] | None = None, base: str = config.BACKEND, path: str = "/console/stream",
             headers: dict | None = None) -> list[dict]:
    """Open the stream, call `trigger` once the first snapshot has arrived, collect events until `until(events)` or timeout.
    Each event is {"event": name, "data": parsed-json-or-str}. Comment lines (': ping') are returned as event 'comment'."""
    events: list[dict] = []
    deadline = time.monotonic() + timeout
    triggered = trigger is None
    h = {"Authorization": f"Bearer {token}", "Accept": "text/event-stream", **(headers or {})}
    with httpx.Client(base_url=base, timeout=httpx.Timeout(timeout, connect=10)) as c:
        with c.stream("POST", path, json=body, headers=h) as r:
            if r.status_code != 200:
                r.read()
                raise AssertionError(f"stream refused: {r.status_code} {r.text[:500]}")
            name, data = None, []
            for line in r.iter_lines():
                if line.startswith(":"):
                    events.append({"event": "comment", "data": line})
                elif line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif line == "" and name:
                    raw = "\n".join(data)
                    try:
                        parsed = json.loads(raw)
                    except ValueError:
                        parsed = raw
                    events.append({"event": name, "data": parsed})
                    name, data = None, []
                    if not triggered and any(e["event"] == "snapshot" for e in events):
                        triggered = True
                        trigger()
                if until(events) or time.monotonic() > deadline:
                    break
    return events
