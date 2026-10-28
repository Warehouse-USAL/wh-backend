# Seeded business metrics

`SEED_DEMO=true` fills a fresh database with **two years (730 days) of daily history** for every
business metric the backend can serve. This page lists them, where each comes from and how it is
tested. Backend-infrastructure series (JVM, HTTP, Kafka, Mongo, MinIO, collector, VictoriaMetrics,
edge) are **not** business metrics and are not seeded: they only exist from the moment the stack runs.

## Where the data comes from

| Source | Seeded by | Resolution | Stored in |
|---|---|---|---|
| Orders, restock orders, receptions | `DemoDataset` + `DemoHistory` (one simulated day at a time: deliveries, orders in time order, a buyer applying the restock formula) | per event, 730 days | MongoDB (`orders`, `restock_orders`, `receptions`) |
| Fleet telemetry (battery, state, transitions) | `DemoTelemetryDataset` + `DemoTelemetrySeeder` | 30 minutes (hourly for the counters), 730 days | VictoriaMetrics (`/api/v1/import`) |

Everything derived from orders (conversion, cycle time, fulfilment, demand...) agrees with itself
because it reads the same documents: the order history is simulated causally (stock never goes
negative; a stockout cancels the order), and the suggestion flag below is decided in a separate
pass keyed by order number, so it changes no other number.

Measured on the reference machine (JDK 21): building the whole dataset takes ~170 ms (about 25 000
orders, 50 000 lines, 1 000 restock orders, 1 200 receptions) and the telemetry ~100 ms (48 series,
1.37 M points); the rest of the seed is the MongoDB bulk insert and the VictoriaMetrics import (the
backend logs `Demo seed took N ms` at the end). Measured end to end in the E2E stack
(Docker, the same containers as CI) the whole seed, MongoDB insert and VictoriaMetrics import included,
took 3.1 s (`Demo seed took 3105 ms`); VictoriaMetrics makes the imported samples searchable a few
seconds after that, so a dashboard queried the very instant the backend is healthy can still see
gaps in the telemetry series.

## Catalogue audit (`GET /metrics/catalog`)

| Metric | Kind | Business? | Source | Seeded daily for 2 years |
|---|---|---|---|---|
| `wh.vehicle.battery` | gauge | yes | telemetry | yes (48 points a day per rover) |
| `wh.vehicle.state` | gauge | yes | telemetry | yes (per state, per rover) |
| `wh.vehicle.transitions` | counter | yes | telemetry | yes (hourly samples; failures every 18 h on one rover, so every day has some) |
| `restock_suggestions` | computed | yes | orders + stock + restock orders, as of now | a snapshot by definition (no time axis); its demand input is the daily order history |
| `suggestion_conversion` | computed | yes | orders (`items[].from_recommendation`) | yes (new, derived from the orders) |
| `http.server.*`, `tomcat.*`, `jvm.*`, `mongo.*`, `kafka.*`, `ws.*`, `bridge.*`, `job.run` | raw | no (backend infrastructure) | OpenTelemetry at runtime | no |
| `minio.*`, `collector.*`, `vm.*`, `edge.*` | raw | no (platform infrastructure) | scraped at runtime | no |

Only `wh.*` series are visible to roles below SUPERADMIN, which is also how the e2e test finds
"the business series" without a hard-coded list.

## Dashboard KPIs (`docs/DASHBOARD_INTEGRATION.md`)

The numbered KPIs are not separate stored series: they are queries over the data above. Each one is
covered by the daily history:

| KPI | Endpoint | Daily coverage |
|---|---|---|
| 1-5 failures, Pareto, MTBF, MTTR, active rovers, battery | `POST /metrics/query` on `wh.vehicle.*` | every day |
| 6, 15, 16, 17, 20 top SKUs, picking, demand, rotation | `POST /query/orders` (unwind items) | every day |
| 7-10, 14 completion, cancellation and SLA per hour/day | `POST /query/orders` grouped by `created_at` bucket and `status` | every day (completed and cancelled orders) |
| 11 productivity per rover | `POST /query/orders` grouped by `assigned_vehicle_id` | every month, every active rover |
| 12, 13 cycle time, time to assignment | `cycle_time_ms`, `assignment_latency_ms` | every day (completed orders) |
| 18, 19, 21 stock, coverage, restock | `POST /query/positions`, `/products`, `/metrics/restock-suggestions` | a snapshot of today (stock levels over time are not stored anywhere; the daily movements are the orders and receptions) |
| inflow (receptions, restock orders) | `POST /query/receptions` | deliveries arrive on some days, not all: present in every month |
| suggestion conversion | `POST /metrics/suggestion-conversion` | every day |

## Suggestion conversion

`POST /orders` accepts an optional boolean `from_recommendation` per item (see `SUGGESTIONS.md`).
In the seed it is set on a share of lines that grows from 4 % (two years ago) to 26 % (today), with
a bump in November-December, and the second and third line of an order are far likelier than the
first (a cart suggestion is an "also add"). A day with orders always has at least one recommended
line, so the daily series has no empty day. The overall line rate is ~12 % (5 849 of 49 835 lines).

## Tests

| What | Where |
|---|---|
| Every day has completed orders with timings, cancellations and receptions spread over 24 months, adoption grows, no empty conversion day | `DemoDatasetTest` |
| Every day has battery/state samples for every rover, and transitions and faults on every day | `DemoTelemetryDatasetTest` |
| The same through the real endpoints, day by day for 730 days: the catalogue's business series, the fleet KPI inputs, `suggestion-conversion` (day, week, month), orders per day/status, cycle time, latency, lines/units, rover productivity, receptions | `e2e/suite/test_business_metrics.py` |

If a new business metric is added to `MetricRegistry`, it appears in `GET /metrics/catalog` for a
non-SUPERADMIN role and `test_every_business_series_of_the_catalog_has_a_point_every_day_for_two_years`
fails until the seed covers it, which is the intended reminder.
