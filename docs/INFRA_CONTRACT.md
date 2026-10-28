# Infra contract: what the real central does, and what the backend assumes

Source of truth for this document: the code of Infra's central, repository
`Warehouse-USAL/G5_Version_NET` (branch `master`, commit `2c436c5`, 2026-10-17: `Orchestrator.cs`,
`MqttGateway.cs`, `OrderRepository.cs`, `VehicleState.cs`, `docs/backend/CONTRATO_BACKEND_INFRA.md`)
plus `Warehouse-USAL/wh-vision-qr` (the Raspberry Pi camera node). Read from source only: the real
broker and rover are never contacted from this repository's tests. What could not be confirmed by
running it is listed in "What is not verified" at the end and is the job of
[STAGING_CHECKLIST.md](STAGING_CHECKLIST.md).

Not to be confused with `Warehouse-USAL/WH-Infra` (only a 2026-05 Python prototype with the old
`backend/orders` / `central/backend_updates` topics and `order.dispatch` / `order.cancel` messages;
nothing of it matches the current contract) nor with the `queue-redundancia` branch of
`G5_Version_NET` (older, without the contract).

Wire contract (field by field): [RFC_SmartWarehouse_Backend.md](RFC_SmartWarehouse_Backend.md) section 11
and wh-backend#128. This document only covers behaviour.

## Pieces and channels

```
backend --Kafka `orders`--> bridge --AMQP amq.topic key `orders`--> RabbitMQ --MQTT topic `orders`--> central
backend <--HTTP-- bridge <--queue wh-backend.vehicles (key `vehicles`)-- RabbitMQ <--MQTT topic `vehicles`-- central
central <--MQTT devices/<id>/{rover,camera}/...--> rover (ESP8266) + camera (Raspberry Pi, vision-qr)
```

* The central is one .NET process with one event loop (`Orchestrator`): every MQTT message, timer and
  watchdog tick is queued and handled one at a time, so there are no races inside it.
* The central connects over MQTT 3.1.1 with the fixed ClientId `central-service` and
  `CleanSession=false`: RabbitMQ keeps its subscription queue, so orders published while the central
  is down are delivered when it comes back. Orders are subscribed with QoS 1, so the bridge's
  `mandatory` publish succeeds as soon as the central connected once.
* Orders are persisted in PostgreSQL (`orders` table, with phase, stop index, forward travel). A message
  is acknowledged to the broker only after the order is saved.
* The central publishes order statuses and vehicle telemetry on the single MQTT topic `vehicles`
  (QoS 1). The bridge tells them apart by the presence of `order_id`.

## Order lifecycle in the central

| Event | Central behaviour | Reported to the backend |
|---|---|---|
| `Order` `pending` with stops | Saved as `pending`; if a vehicle is free (rover and camera online, no run) it is claimed (oldest first) | `OrderStatus in_progress` with `vehicle_id` at assignment, then telemetry `busy` |
| `Order` `pending`, no stops | Not executed | `OrderStatus cancelled` (no vehicle) |
| `Order` `pending` already known (same `order_id`) | Ignored (also when it was cancelled before it arrived) | nothing |
| Per stop | Tells the camera the target QR (`set_target`, `leg_id = <order>:<index>`), drives forward, on `qr_detected` matching stop and leg it stops, waits `DwellSec` (5 s), continues | telemetry with `last_qr` = the stop |
| QR not seen within `LegTimeoutSec` (30 s) | Aborts: reverses for the time driven forward | at the end `OrderStatus cancelled`; meanwhile telemetry `busy` with `error.code=STOP_NOT_DETECTED` |
| All stops done | Reverses for the time driven forward (`ReturnTimeFactor`, `ReturnExtraSec`) | `OrderStatus completed`, then telemetry `idle` |
| Rover or camera drops | Run is paused (vehicle commanded to stop if still reachable); resumes by itself on reconnection | telemetry `error` with `CONNECTION_LOST`; order stays `in_progress` |
| Central restarts | Active runs are reloaded from PostgreSQL, paused until the vehicle shows up online, then the current phase is re-sent | nothing special; telemetry restarts from the first device report; `last_qr` is `null` until a new QR is seen |

Reverse is **open loop**: the central does not look for a QR on the way back, it drives backwards for
the same time it drove forward (times are recomputed from the accumulated forward travel).

### Cancel (`Order` with `status: cancelled`)

Handled immediately by the event loop (`HandleCancelAsync`), never queued behind other orders:

| Situation | Central behaviour | Reported to the backend |
|---|---|---|
| Order waiting in the queue | Marked cancelled, leaves the queue | nothing |
| Order on a vehicle, moving or waiting at a stop | Marks `CANCELLED_BY_BACKEND`, cancels the stop timer, clears the camera target, **reverses for the time driven forward** (phase `Aborting`) | telemetry stays `busy` while driving back; **no order status at all**; when the vehicle is back, the run is closed as `cancelled` internally and telemetry goes `idle` |
| Order already returning (all stops done) | Only the end state changes: it is closed as cancelled, **`completed` is never sent** | same as above: telemetry `idle`, no order status |
| Order paused (vehicle disconnected) | Phase `Aborting`, the reverse starts as soon as the rover (camera not needed) is online | telemetry `error` until then |
| Cancel arrives before the order | Registered as cancelled; the later `pending` is ignored | nothing |
| Order already finished | Ignored | nothing |

The next queued order is dispatched only after the vehicle is idle again (end of the reverse).

## What the backend does (and assumes)

* `POST /orders/{id}/cancel` (any state not terminal): marks the order `CANCELLED` **at once**, releases
  the stock reservation, publishes `Order(cancelled)` on `orders`, and for an `IN_PROGRESS` order
  clears `currentOrderId` on the assigned vehicle. The vehicle's **status is not touched**: it keeps
  following Infra's telemetry (`OrderService.cancelOrder`).
* A status Infra sends for an order that is already terminal is answered 409 `ORDER_NOT_MODIFIABLE`
  (the bridge logs it and acks: 4xx are never retried). Same status again: 200, no-op.
  That is why a late `completed` after a cancel does not drain stock, and why a duplicated
  `completed` drains it only once.
* `completed` releases the vehicle (`IDLE`, `currentOrderId=null`) without waiting for telemetry.
  A `cancelled` reported by Infra (rejected order, or `STOP_NOT_DETECTED`) is applied and also releases it.
* Telemetry sets `status`, `battery`, `lastSeenAt` and the operation window; it is the only thing that
  moves a vehicle out of `BUSY` after a cancel.
* The backend has **no timeout** on `in_progress` orders and **no staleness check** on vehicles
  (`lastSeenAt` is stored but nothing compares it with the clock).

## Failure modes

| Failure | What happens | Detectable by | Recovery |
|---|---|---|---|
| Cancel lost on the way (bridge or broker down when the admin cancels, never delivered) | Backend: order cancelled, stock released. Rover keeps going, finishes, reports `completed` -> 409 discarded. **Stock is not drained although the goods may have moved** | `bridge` logs `rejected 409` for an order that is `cancelled`; vehicle telemetry shows `busy` for longer than the cancel | The cancel is not lost while the Kafka offset is uncommitted and the broker holds QoS 1 messages: it is delivered late (the rover keeps driving until then). Reconcile stock by hand after counting |
| Infra restarts mid order | Run resumes from PostgreSQL after the vehicle reappears; no duplicate `in_progress` | telemetry gap, then `error`/`busy` | none needed. If the database was lost the order is forgotten: it stays `in_progress` in the backend forever (no timeout) and the vehicle reads `idle` -> admin cancels the order |
| Duplicate / late order status | Same status: 200 no-op. Other status on a terminal order: 409, discarded | bridge logs | none |
| `in_progress` for an order cancelled in the meantime | 409, discarded; the vehicle drives (the cancel is queued behind it in the same Kafka partition key, so the central sees `pending` then `cancelled`) and returns | bridge logs; vehicle `busy` briefly | none |
| Vehicle telemetry never says `idle` after a cancel | Backend leaves the vehicle `busy` with `currentOrderId=null`; the next order is dispatched by Infra anyway (Infra decides with its own state), whose `in_progress` marks it busy again | `GET /vehicles`: `status=busy` and `current_order_id=null` and old `last_seen_at` | An `idle` reading frees it. The backend does not guess: see "Decision on reconciliation" |
| Rover/camera lost during a cancel return | Telemetry `error`/`offline`; the return waits for the rover | `vehicle.error` WebSocket event, console | Reconnect the rover; the run resumes the reverse |
| Reverse is time based | If the rover slipped or moved by hand the final position is not verified | physical check in staging | operator puts the rover at start |
| MQTT drop while the central finishes a run | `PublishAsync` returns false and the status is **not retried** (a `completed` or `in_progress` can be lost) | order stays `in_progress` in the backend | admin cancels or completes it by hand (`/orders/{id}/status` is internal; use cancel) |
| Central never connected to this broker | No subscription queue is bound to `orders`; the bridge's `mandatory` publish fails and **retries** instead of losing orders | bridge logs / `/console` status | start the central once so its queue exists |
| Telemetry rate | About every 30 s per vehicle plus on change (status, error, `last_qr`). Offline timeout is 70 s | | |

## Decision on reconciliation (gap: vehicle left `busy` after a cancel)

Evidence: the real central always sends `idle` at the end of the return (it closes the run and calls
`ReportVehicleAsync` before dispatching anything else) and repeats telemetry every ~30 s, so a vehicle
cannot stay `busy` after a cancel unless the central stops talking, in which case `offline` is the true
state and `lastSeenAt` shows it. The only path that leaves a stale `busy` is Infra silence, and then
the backend cannot know whether the rover is still driving. Marking it `idle` on a timer could send a
second order to a moving rover in the operator's mind (the console would offer it as free), so
**no automatic release is implemented**. The cancel already clears `currentOrderId`, and an `idle`
reading (or `completed`) frees the vehicle. What is covered:

* e2e `test_infra_failures.py::test_a_vehicle_that_never_reports_idle...` pins the behaviour (busy with
  no order, freed by the next `idle`).
* Operators and monitoring can detect a stuck vehicle with `GET /vehicles?size=50`:
  `status == "busy" and current_order_id == null and now - last_seen_at > 2 minutes`
  (the central reports every ~30 s). `docs/STAGING_CHECKLIST.md` includes this check.

Open decision for the owner: an in-progress order has no timeout either. If wanted, add an
`@Scheduled`-style job (cluster safe, see `docs/E2E.md` "Multiple instances") that alerts on in-progress
orders older than N minutes. Not done: it would add a second source of truth next to Infra.

## How the fake Infra models this

`bridge/cmd/fakeinfra` follows the tables above (queue, one vehicle, per-stop telemetry with `last_qr`,
return time equal to forward time, cancel handled at once, no status for a cancelled run, duplicate
orders ignored, orders without stops answered `cancelled`). Environment (all optional):

| Variable | Default | Meaning |
|---|---|---|
| `FAKE_VEHICLE_ID` | `VHC-001` | vehicle id |
| `FAKE_STEP_DELAY` | `1s` | time per stop (real: 5 s dwell plus travel) |
| `FAKE_RETURN_FACTOR` | `1` | reverse time / forward time |
| `FAKE_TELEMETRY_INTERVAL` | `0` (on change only) | periodic telemetry (real: 30 s) |
| `FAKE_DROP_CANCELS` | `false` | lost cancel |
| `FAKE_DUPLICATE_STATUSES` | `false` | every order status twice |
| `FAKE_STUCK_BUSY` | `false` | never reports `idle` after a run |

At run time publish JSON to `amq.topic` key `fakeinfra.control` (the e2e suite does):
`{"drop_cancels":true,"duplicate_statuses":false,"stuck_busy":false,"step_delay":"3s","restart":true}`.
`restart` forgets every order, like a central that lost its database.

Differences from the real one (deliberate): one stop is one timer (no camera / QR matching, no dwell
or `STOP_NOT_DETECTED`); a restart forgets orders instead of resuming them from PostgreSQL; no
`CONNECTION_LOST` / pause; no 30 s telemetry unless asked.

## What is not verified

Nothing here was run against the real central, broker or rover. Unverified assumptions, all covered
by the checklist: that the deployed central is `G5_Version_NET` `master` at or after `2c436c5`;
that `Orders`/`Vehicles` topics are `orders`/`vehicles` as in `appsettings.json`; the real durations
of the return after a cancel; that the rover really returns to its start; the behaviour of the QR
text format (`WH2|PA1` in Infra's examples versus `WH|<zone>-L<line>-<position>` in this repository's
seed; the backend takes it from `WH_QR_PREFIX` and the positions' `qr_code`).
