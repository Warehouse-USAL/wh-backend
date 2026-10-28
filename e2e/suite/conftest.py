"""Shared fixtures, group ordering, known-failure handling and the results matrix.

Groups run in this order inside one session: default (fake Infra stopped, tests play Infra over HTTP),
`flow` (fake Infra running), `resilience` (stops/starts containers).
"""
import os
import time
from collections import OrderedDict

import pytest

from lib import config
from lib.api import Api, login, wait_until
from lib.factory import Factory, InfraClient, uid
from lib.infra import KafkaTap, Rabbit, Stack, redis_client

RESULTS_DIR = "/results"


# ─────────────────────────────── collection: ordering + known failures ───────────────────────────
def pytest_collection_modifyitems(config, items):
    def group(item):
        if item.get_closest_marker("resilience"):
            return 2
        if item.get_closest_marker("flow"):
            return 1
        return 0

    items.sort(key=group)  # stable: files keep their order inside a group
    for item in items:
        kf = item.get_closest_marker("known_failure")
        if kf:
            reason = kf.kwargs.get("reason") or (kf.args[0] if kf.args else "known product bug")
            bug = kf.kwargs.get("bug")
            item.add_marker(pytest.mark.xfail(reason=f"{bug + ': ' if bug else ''}{reason}", strict=True))


def pytest_sessionstart(session):
    # Don't start before the whole stack answers: `up -d` returns as soon as containers exist.
    def ready():
        return Api().get("/actuator/health", token=None).status == 200 and Api(config.CONSOLE).get("/console/", token=None).status == 200
    wait_until(ready, timeout=300, interval=3, desc="backend /actuator/health and console UI")


# ─────────────────────────────── infrastructure fixtures ───────────────────────────────
@pytest.fixture(scope="session")
def stack() -> Stack:
    return Stack()


@pytest.fixture(scope="session")
def kafka():
    tap = KafkaTap()
    yield tap
    tap.close()


@pytest.fixture(scope="session")
def rabbit() -> Rabbit:
    r = Rabbit()
    wait_until(lambda: r.http.get("/api/overview").status_code == 200, timeout=60, desc="RabbitMQ management API")
    r.ensure_sink()
    return r


@pytest.fixture(scope="session")
def redis_c():
    return redis_client()


@pytest.fixture(scope="session", autouse=True)
def _sink(rabbit):
    """The sink queue must exist before the first order: the bridge publishes `mandatory` and retries
    forever while nothing is bound to `orders` (fakeinfra only binds its own queue when started)."""


@pytest.fixture(autouse=True)
def _flow_guard(request):
    if request.node.get_closest_marker("flow"):
        request.getfixturevalue("fakeinfra")


@pytest.fixture(scope="session")
def fakeinfra(stack, rabbit):
    """Starts the fake Infra (created but stopped by e2e/suite.sh) the first time a flow test needs it."""
    if stack.status("fakeinfra") != "running":
        if rabbit.exists("fakeinfra.orders"):
            rabbit.purge("fakeinfra.orders")  # leftovers of an earlier run on a kept stack
        stack.start("fakeinfra")
        wait_until(lambda: rabbit.exists("fakeinfra.orders"), timeout=60, desc="fakeinfra bound its queue")
    return stack.container("fakeinfra")


# ─────────────────────────────── users / roles ───────────────────────────────
@pytest.fixture(scope="session")
def anon() -> Api:
    return Api(name="anon")


@pytest.fixture(scope="session")
def admin() -> Api:
    return login(*config.ACCOUNTS["SUPERADMIN"])


@pytest.fixture(scope="session")
def system() -> Api:
    return login(*config.ACCOUNTS["ADMIN_SYSTEM"])


@pytest.fixture(scope="session")
def warehouse() -> Api:
    return login(*config.ACCOUNTS["ADMIN_WAREHOUSE"])


@pytest.fixture(scope="session")
def sales() -> Api:
    return login(*config.ACCOUNTS["ADMIN_SALES"])


@pytest.fixture(scope="session")
def provider() -> Api:
    return login(*config.ACCOUNTS["PROVIDER"])


@pytest.fixture(scope="session")
def dispatcher() -> Api:
    return login(*config.ACCOUNTS["DISPATCHER"])


@pytest.fixture(scope="session")
def operator() -> Api:
    return login(*config.ACCOUNTS["OPERATOR"])


@pytest.fixture(scope="session")
def operator2() -> Api:
    return login("operator2@smartwarehouse.local", config.DEMO_PASSWORD)


@pytest.fixture(scope="session")
def dashboard(admin) -> Api:
    """No seeded DASHBOARD account: create one."""
    f = Factory(admin, admin)
    u = f.user(admin, "DASHBOARD", password="Dashb0ard!x1")
    return login(u.email, u.password)


@pytest.fixture(scope="session")
def role_apis(admin, system, warehouse, sales, provider, dispatcher, operator, dashboard) -> dict:
    return {"SUPERADMIN": admin, "ADMIN_SYSTEM": system, "ADMIN_WAREHOUSE": warehouse, "ADMIN_SALES": sales,
            "PROVIDER": provider, "DISPATCHER": dispatcher, "OPERATOR": operator, "DASHBOARD": dashboard}


@pytest.fixture(scope="session")
def factory(warehouse, sales) -> Factory:
    return Factory(warehouse, sales)


@pytest.fixture(scope="session")
def infra(factory) -> InfraClient:
    return factory.infra


@pytest.fixture
def vehicle_id():
    """A fresh Infra-side vehicle id plus the backend id derived from it."""
    infra_id = f"VHC-E2E-{uid(8)}"
    return infra_id, InfraClient.backend_vehicle_id(infra_id)


# ─────────────────────────────── results matrix ───────────────────────────────
_results: "OrderedDict[str, dict]" = OrderedDict()


def _area(nodeid: str) -> str:
    f = nodeid.split("::")[0].rsplit("/", 1)[-1]
    return f.removeprefix("test_").removesuffix(".py")


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    rep = outcome.get_result()
    doc = (getattr(item.function, "__doc__", "") or "").strip().splitlines()
    if rep.when == "setup" and rep.outcome != "passed" or rep.when == "call" or (rep.when == "setup" and rep.skipped):
        status = rep.outcome
        if hasattr(rep, "wasxfail"):
            status = "known-failure" if rep.skipped else "FAIL(xpass)"
        elif rep.outcome == "failed" and rep.when == "setup":
            status = "error"
        reason = ""
        if rep.outcome == "failed" and rep.longrepr:
            reason = str(rep.longrepr).strip().splitlines()[-1][:300]
        elif hasattr(rep, "wasxfail"):
            reason = rep.wasxfail
        elif rep.skipped and isinstance(rep.longrepr, tuple):
            reason = rep.longrepr[2]
        _results[item.nodeid] = {"area": _area(item.nodeid), "name": item.name, "status": status,
                                 "doc": doc[0] if doc else "", "reason": reason, "time": rep.duration,
                                 "group": "resilience" if item.get_closest_marker("resilience") else
                                 "flow" if item.get_closest_marker("flow") else "default"}


def pytest_sessionfinish(session, exitstatus):
    if not _results:
        return
    os.makedirs(RESULTS_DIR, exist_ok=True)
    areas: "OrderedDict[str, dict]" = OrderedDict()
    for r in _results.values():
        a = areas.setdefault(r["area"], {"total": 0, "pass": 0, "fail": 0, "known": 0, "skip": 0})
        a["total"] += 1
        key = {"passed": "pass", "known-failure": "known", "skipped": "skip"}.get(r["status"], "fail")
        a[key] += 1
    tot = {k: sum(a[k] for a in areas.values()) for k in ("total", "pass", "fail", "known", "skip")}
    out = ["# E2E results matrix", "", f"Generated {time.strftime('%Y-%m-%d %H:%M:%S')} UTC-local. "
           f"Total {tot['total']}: {tot['pass']} pass, {tot['fail']} fail, {tot['known']} known-failure, {tot['skip']} skipped.", "",
           "## By area", "", "| Area | Total | Pass | Fail | Known failure | Skipped |", "|---|---|---|---|---|---|"]
    for name, a in areas.items():
        out.append(f"| {name} | {a['total']} | {a['pass']} | {a['fail']} | {a['known']} | {a['skip']} |")
    out.append(f"| **all** | {tot['total']} | {tot['pass']} | {tot['fail']} | {tot['known']} | {tot['skip']} |")
    out += ["", "## Every test", "", "| Test id | Capability | Group | Status | Note |", "|---|---|---|---|---|"]
    for nodeid, r in _results.items():
        note = r["reason"].replace("|", "\\|")
        out.append(f"| `{nodeid.split('/')[-1]}` | {r['doc'].replace('|', '/')} | {r['group']} | {r['status']} | {note} |")
    with open(os.path.join(RESULTS_DIR, "matrix.md"), "w") as fh:
        fh.write("\n".join(out) + "\n")
