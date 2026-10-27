"""HTTP client with failure messages that carry the whole request/response."""
import json
import time
from typing import Any, Callable

import httpx

from . import config

_REDACT = {"authorization", "x-api-key"}


class Resp:
    """An httpx response plus .expect(), which explains itself when the status is wrong."""

    def __init__(self, response: httpx.Response):
        self.response = response

    def __getattr__(self, name: str):
        return getattr(self.response, name)

    @property
    def status(self) -> int:
        return self.response.status_code

    @property
    def body(self) -> Any:
        try:
            return self.response.json()
        except ValueError:
            return self.response.text

    @property
    def code(self) -> str | None:
        """The error code of a {"error": {"code": ...}} body (backend) or {"error": "..."} (suggestions)."""
        b = self.body
        if isinstance(b, dict):
            e = b.get("error")
            if isinstance(e, dict):
                return e.get("code")
            if isinstance(e, str):
                return e
        return None

    def describe(self) -> str:
        req = self.response.request
        headers = {k: ("<redacted>" if k.lower() in _REDACT else v) for k, v in req.headers.items()
                   if k.lower() in {"authorization", "x-api-key", "content-type", "idempotency-key"}}
        try:
            sent = req.content.decode(errors="replace")[:1500] if req.content else ""
        except Exception:  # streamed multipart bodies cannot be re-read
            sent = "<streamed body>"
        return (f"\n  > {req.method} {req.url}\n  > headers: {headers}\n  > body: {sent}"
                f"\n  < {self.response.status_code}\n  < {self.response.text[:2000]}")

    def expect(self, *statuses: int, code: str | None = None) -> "Resp":
        statuses = statuses or (200,)
        if self.status not in statuses:
            raise AssertionError(f"expected status {statuses}, got {self.status}{self.describe()}")
        if code is not None and self.code != code:
            raise AssertionError(f"expected error code {code!r}, got {self.code!r}{self.describe()}")
        return self

    def json_(self, *path: str) -> Any:
        """Body (after expect) optionally dug into by keys: r.json_('order', 'id')."""
        cur = self.body
        for p in path:
            try:
                cur = cur[p]
            except (KeyError, TypeError, IndexError):
                raise AssertionError(f"no {'.'.join(path)!r} in response{self.describe()}") from None
        return cur


class Api:
    def __init__(self, base: str = config.BACKEND, token: str | None = None, name: str = "anon",
                 user: dict | None = None, headers: dict | None = None):
        self.base, self.token, self.name, self.user = base, token, name, user or {}
        self.extra_headers = headers or {}
        self.http = httpx.Client(base_url=base, timeout=30)

    def request(self, method: str, path: str, *, token: str | None | bool = True, **kw) -> Resp:
        headers = dict(self.extra_headers)
        headers.update(kw.pop("headers", {}) or {})
        tok = self.token if token is True else token
        if tok:
            headers.setdefault("Authorization", f"Bearer {tok}")
        return Resp(self.http.request(method, path, headers=headers, **kw))

    def get(self, path, **kw): return self.request("GET", path, **kw)
    def post(self, path, **kw): return self.request("POST", path, **kw)
    def patch(self, path, **kw): return self.request("PATCH", path, **kw)
    def put(self, path, **kw): return self.request("PUT", path, **kw)
    def delete(self, path, **kw): return self.request("DELETE", path, **kw)

    @property
    def id(self) -> str:
        return self.user.get("id", "")


def login(email: str, password: str, base: str = config.BACKEND) -> Api:
    r = Api(base).post("/auth/login", json={"email": email, "password": password}).expect(200)
    return Api(base, r.json_("token"), name=r.json_("user", "role"), user=r.json_("user"))


def wait_until(fn: Callable[[], Any], timeout: float = 30, interval: float = 0.5, desc: str = "condition"):
    """Poll fn until it returns something truthy (returned). Exceptions count as 'not yet'.
    On timeout fails with the last value/exception so the failure says what was seen."""
    deadline = time.monotonic() + timeout
    last: Any = None
    while True:
        try:
            last = fn()
            if last:
                return last
        except (AssertionError, httpx.HTTPError, OSError, KeyError, TypeError) as e:  # noqa: PERF203
            last = f"{type(e).__name__}: {e}"
        if time.monotonic() >= deadline:
            raise AssertionError(f"timed out after {timeout}s waiting for {desc}; last observed: {str(last)[:1500]}")
        time.sleep(interval)


def pretty(x: Any) -> str:
    return json.dumps(x, indent=2, default=str)[:3000]
