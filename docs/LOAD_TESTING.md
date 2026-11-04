# Load testing (`make load`)

A repeatable load harness for the backend. It is NOT part of the PR checks: it needs several minutes, a lot of CPU and the numbers depend on the machine. Run it before a release that touches orders, listings, metrics or streams, and compare with the baselines below.

## How to run

```
make load                                           # everything (about 10 min after the images are built)
LOAD_SCENARIOS="02_listing 03_orders" load/load.sh  # a subset
KEEP=1 load/load.sh                                 # leave the stack up; LOAD_REUSE=1 reuses it (and its secrets) next time
```

Requirements: Docker with compose v2, 8 GB RAM, 10 GB free disk (the script refuses to start below `MIN_FREE_GB`, default 10). It uses its own compose project (`whload`, override with `LOAD_PROJECT`), publishes no host ports, builds the same images as the e2e suite, seeds the demo dataset (about 25,000 orders over 730 days, so listings and metrics run on realistic volumes), generates throwaway secrets, runs each scenario one at a time while sampling `docker stats` every 2 s, prints the MongoDB query plans of the hot reads and tears everything down. Output (git-ignored) in `load/results/`: `summary.md`, one `k6-<scenario>.json` and `stats-<scenario>.csv` per scenario, `explain-plans.txt`.

Tools: [k6](https://k6.io) (image `grafana/k6:2.3.0`) for HTTP scenarios, an asyncio Python client (`load/py/streams.py`) for SSE and WebSocket. The bridge and RabbitMQ are not started (orders are created, not dispatched).

## Scenarios, thresholds and baselines

Baseline machine: Docker Desktop on macOS, 10 CPUs, 7.75 GiB; one backend instance (no CPU limit), MongoDB 4.4 replica set of one, Redpanda. Numbers are a reference, not a promise.

| Scenario | Load | Threshold (fails the run) | Baseline |
|---|---|---|---|
| `01_login` | 40 logins/s for 30 s | login p95 < 1.5 s, p99 < 3 s, errors < 1% | p50 61 ms, p95 67 ms, p99 73 ms, 0% errors; backend about 260% CPU (bcrypt) |
| `02_listing` | 30 VUs, products/orders/vehicles lists, filters and deep pages | products p95 < 500 ms, orders p95 < 800 ms, deep page p95 < 2 s, `/query/orders` p95 < 1 s, errors < 1% | about 1,700 req/s, p95 39 ms, p99 54 ms, deep page p95 52 ms, 0% errors; MongoDB about 570% CPU |
| `03_orders` | 40 orders/s for 30 s on abundant stock, then 50 VUs x 10 orders on a product with 100 units | abundant p95 < 1.5 s, p99 < 3 s, errors < 5%; scarce: exactly 100 sold, no 5xx, never `reserved > physical` | 1,719 requests, abundant p95 about 0.6 s; scarce: 100 sold, 373 `INSUFFICIENT_STOCK`, 27 retryable `409 STOCK_CHANGE_CONFLICT`, 0 unexpected; stock check passes |
| `04_metrics` | metrics endpoints under concurrent users | conversion p95 < 3 s, restock p95 < 5 s, `/metrics/query` p95 < 2 s, errors < 1% | `suggestion-conversion` p95 480 ms / p99 862 ms, `restock-suggestions` p95 160 ms, `/metrics/query` p95 20 ms; backend about 715% CPU, up to 2 GB |
| `05_suggestions` | 200 reads/s on the suggestions service (hot, cart, user) while orders complete | p95 < 100 ms, p99 < 250 ms, errors < 1% | p95 23 ms, p99 56 ms |
| `sse` | 60 console SSE clients | cap respected | 50 accepted, 10 refused with 429 (backend-wide cap 50, 10 per principal); first snapshot p50 257 ms, p95 349 ms |
| `ws` | 200 WebSocket clients on `/ws/v1/orders`, 30 orders created | every event delivered | 6,000/6,000 events (ratio 1.0), latency p50 18 ms, p95 43 ms, p99 55 ms |

## Findings

- **No missing index was found.** `explain` on the hot reads (24-26k orders): filters by status, dates, requester and sorted queries use `status_createdAt_idx`/`createdAt` with tiny scans; the reserved-stock query uses `status_createdAt_idx`; `suggestion-conversion` over 730 days examines about 22.8k documents in 19 ms.
- **`GET /orders` without sort was a collection scan** with skip (a deep page examines about 20,000 documents, 5-14 ms). Not a bottleneck at this size; the default `createdAt desc` sort that fixes the page-0 contents (BUG-ORDERS-3, PR "deferred contract fixes") also makes it index-backed.
- **Bottlenecks are CPU, not the database.** (1) Login: bcrypt, one core per login at 40/s (about 260% CPU for the backend). The only fix is to lower the cost factor (a security trade-off) or scale out; leave as is. (2) Metrics endpoints are the heaviest reads: `suggestion-conversion` loads all orders of the range into memory (backend 2 GB peak in the metrics scenario). Keep ranges short on the dashboards; consider streaming/aggregation in MongoDB if the order volume grows 10x.
- **Order creation on one product conflicts under heavy contention.** With 50 clients hammering a single product, about 5% of the requests (27 of 500 in the last run, 61 in another) lose the write conflict after the service's 40 retries and answer `409 STOCK_CHANGE_CONFLICT` (documented, retryable by the client); the stock invariant held in every run (no oversell). Spread across different products this does not occur.
- **Streams**: the SSE caps work (429 beyond them). WebSocket fan-out to 200 local clients is cheap; the limitation is multi-instance (BUG-MULTI-1 in [E2E.md](E2E.md)).
- Memory: backend 0.7-0.8 GB in the order scenarios, 2 GB in the metrics scenario; MongoDB 0.2 GB; Redpanda and VictoriaMetrics 0.4-0.7 GB each. Size the production host with at least 4 GB free for the backend JVM.

## Maintaining

- Scenario scripts: `load/k6/*.js` (shared helpers in `lib.js`: login, stocked product factory, order bodies); thresholds are in each script's `options`. When a threshold fails the scenario is shown as `FAILED` in `summary.md`.
- A first run after a code change that moves a baseline by more than 2x deserves an `explain` check (`load/explain.js`).
