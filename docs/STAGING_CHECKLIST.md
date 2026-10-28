# Staging checklist: validating the backend against the REAL Infra

This is the part nothing in this repository can automate: the real central, RabbitMQ and rover are
only reachable from the warehouse network. Run it in a staging window, with a person next to the rover
and the emergency stop. Background and the exact behaviour of the central:
[INFRA_CONTRACT.md](INFRA_CONTRACT.md). Every step says what to run, what you must see, and what to
do if you do not. Tick the box and paste the evidence (log lines, screenshots) into the PR or ticket.

Conventions: `H` is the backend base URL (`https://<public-host>/api`), `TOKEN` a SUPERADMIN or
ADMIN_WAREHOUSE JWT (`POST $H/auth/login`), `PROD="docker compose -f docker-compose.yml -f docker-compose.prod.yml --profile infra"`
on the server. Credentials come from the server's `.env`; never paste them into tickets.

## 0. Before you start

- [ ] A written window (start/end), someone at the rover with the emergency stop, nobody placing real
      customer orders. Rover on its track with free space ahead and the way back clear.
- [ ] Release under test: `git -C /opt/wh/wh-backend describe --tags` and `grep WH_IMAGE_TAG .env`. Write both down.
- [ ] Infra central version: `G5_Version_NET` `master` at or after `2c436c5` (the Infra team confirms;
      the central logs `Central conectada al broker MQTT` on start). An older central (topics
      `backend/orders`, `central/backend_updates`, `order.dispatch`) does **not** speak this contract: stop here.
- [ ] Data backup of MongoDB taken (`docs/OPERATIONS.md`). Staging orders will be created and cancelled.
- [ ] You know the rollback (section 11) before step 1.

## 1. Plumbing

| Check | Command | Expected |
|---|---|---|
| Bridge configured and healthy | `$PROD ps bridge` ; `docker inspect -f '{{.State.Health.Status}}' wh-backend-bridge-1` | `healthy`. Not running means `INFRA_AMQP_URL`/`INFRA_API_KEY` are unset (`docs/OPERATIONS.md`) |
| AMQP port, not MQTT | `grep INFRA_AMQP_URL .env \| sed 's/:[^:@]*@/:***@/'` | `amqp://...:5672/` (never 1883) |
| Bridge sees the broker | `docker logs --tail 50 wh-backend-bridge-1` | no `dial rabbitmq` / `Exception (501)` errors |
| Vehicles queue bound exactly | RabbitMQ panel `http://<infra-host>:15672` > Queues > `wh-backend.vehicles` > Bindings | one binding: `amq.topic` with routing key `vehicles` (no `#`) |
| Central connected once | same panel > Queues: a queue named like `mqtt-subscription-central-serviceqos1` exists | present. If absent the bridge keeps retrying publishing orders (nothing is lost): start the central |
| Central status page | `http://<infra-host>:8080` (or `/api/status`) | `mqtt_connected: true`, `db_ok: true`, your vehicle listed with `rover_online` and `camera_online` true |
| Backend sees the vehicle | `curl -s "$H/vehicles?size=50" -H "Authorization: Bearer $TOKEN" \| jq '.vehicles[] \| {id,name,status,battery,current_order_id,last_seen_at}'` | the vehicle (name = Infra id, e.g. `VHC-001`), `status: idle`, `last_seen_at` less than 60 s old, `current_order_id: null` |
| API key matches | `docker logs wh-backend-backend-1 2>&1 \| grep -c INVALID_API_KEY` | `0` |

If `last_seen_at` does not move, the bridge is not delivering telemetry: check the queue depth of
`wh-backend.vehicles` and the bridge log (4xx lines are discarded on purpose, 5xx are retried).

## 2. QR texts

- [ ] Every position that holds stock has a `qr_code` equal to the text printed in the warehouse (the
      loop in `docs/OPERATIONS.md`, "Checking the codes"). Infra compares the text read by the camera with
      the stop **character by character** (`WH2|PA1`).
- [ ] Pick two positions P and Q whose products you will order. Note their `qr_code`, in traversal order
      (zone, line, position name). Both must have at least 3 units of stock.

## 3. Happy path (one stop)

1. Create an order for 1 unit of the product in P:
   `curl -s -X POST "$H/orders" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"items":[{"product_id":"<id>","quantity":1}],"destination_area":"STAGING","address":{"street":"1 Staging","department":"QA","floor":"1","postal_code":"00001"}}'`
2. Watch, in parallel: the central status page, `docker logs -f wh-backend-bridge-1`, and
   `watch -n2 "curl -s $H/orders/<id> -H 'Authorization: Bearer $TOKEN' | jq '.order | {status,assigned_vehicle_id}'"`.

Expected, in this order:

- [ ] Within seconds: order `pending` -> `in_progress` with `assigned_vehicle_id` = the vehicle (central log
      `Orden <id> asignada a <vehicle>`; bridge log shows one status delivered with 2xx).
- [ ] The rover drives forward, stops at P (central log `llegó a <qr>`), waits about 5 s.
- [ ] The vehicle telemetry shows `status: busy` and later `idle`; `GET /vehicles` shows `current_order_id`
      equal to the order while it runs.
- [ ] The rover reverses to the start. Then the order becomes `completed`, stock `physical` drops by 1 and
      `reserved` returns to 0 (`curl $H/products/<id> | jq .product.stock`), and the vehicle is `idle` with
      `current_order_id: null`.
- [ ] The order was cancelled instead? Then `stops` were empty or a QR was not detected in 30 s: see
      `docs/OPERATIONS.md` "What to do if orders get cancelled immediately" and the central's event list.

Record the time from `in_progress` to `completed` (the reference for the next sections).

## 4. Cancel a queued order (no vehicle assigned yet)

- [ ] With the rover busy on an order (start a long order first, 2 stops), create a second order, then
      `POST $H/orders/<second>/cancel`.
- [ ] Immediately: `status: cancelled`, stock released. In the central the order leaves the queue
      (`cancelada antes de asignarse`). When the first order ends the rover does **not** take the cancelled one.

## 5. Cancel an order in progress with the real rover (the critical test)

This is what the 2026-10 hotfix changed: the backend cancels at once and no longer waits for Infra.

1. Create an order with 2 stops (P and Q). Wait until the rover is between the stops or stopped at the first one.
2. Note the time, then: `curl -s -X POST "$H/orders/<id>/cancel" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"reason":"staging cancel"}'`

Expected:

- [ ] The call answers 200 with `status: cancelled` in well under a second.
- [ ] `GET /orders/<id>`: `cancelled`; product stock `reserved` back to 0 and `physical` unchanged.
- [ ] `GET /vehicles/<vehicle>`: `current_order_id: null`, `status` still `busy` (the backend follows telemetry).
- [ ] The central logs `Orden <id> cancelada ... vuelve al inicio` and its event list shows `cancel_requested`
      then `aborting`. The rover **reverses** for about as long as it drove forward (compare with the time
      you noted) and stops at the start position. **Check by eye that it really is back at the start.**
- [ ] When it stops: the vehicle telemetry goes `idle` (usually within a few seconds of the rover stopping; at
      worst the next 30 s report). `GET /vehicles` then shows `idle`, `current_order_id: null`.
- [ ] No `completed`/`cancelled` order status is sent by Infra for this order (the bridge log has no status
      message for it). If one arrives anyway it is answered 409 and discarded: harmless.
- [ ] The order stays `cancelled`, stock untouched. Create a new order: the rover takes it normally.

If the rover does not reverse, keeps going forward, or the vehicle never returns to `idle` within a minute
after it stopped: press the emergency stop, collect the central's `/api/status` JSON and the last 200
central log lines, and stop the session (this is the failure the owner needs to know about).

Variants (repeat step 5 each time, one order per variant):

- [ ] Cancel while the rover is **stopped at a stop** (dwell, 5 s window: have the second person press cancel on the stop).
- [ ] Cancel **right after creation**, before the rover is assigned (race with the central claiming it): the order
      must end `cancelled` and the rover must not move, or must return and idle.
- [ ] Cancel while the rover is **returning** after the last stop: the central closes the run as cancelled and
      never sends `completed`; the backend order stays `cancelled`, stock released (not drained).

## 6. Duplicates and late messages

- [ ] In the RabbitMQ panel, publish the same cancel again (exchange `amq.topic`, routing key `orders`,
      payload `{"order_id":"<id>","status":"cancelled"}`): the central logs `ignorada: ya estaba ...`; nothing moves.
- [ ] Publish the original `pending` order JSON again for a finished `order_id`: `Orden <id> ya registrada, se ignora`.
- [ ] Publish a cancel for an `order_id` the central never saw: it is registered as cancelled and a later `pending` with
      that id is ignored. (Use ids prefixed `STG-`; the backend does not know them, a 404 in the bridge log for statuses is fine.)

## 7. Lost or delayed cancel

The cancel travels backend -> Kafka -> bridge -> RabbitMQ -> central. While any hop is down the rover keeps
driving although the backend already shows the order cancelled.

- [ ] Start a 2-stop order. `$PROD stop bridge`. Cancel the order. Observe: backend `cancelled`, rover **keeps going**.
- [ ] `$PROD up -d bridge` before the rover finishes: the cancel is delivered late and the rover turns back.
- [ ] Repeat letting the rover finish: Infra sends `completed`; the backend answers 409 (`ORDER_NOT_MODIFIABLE`),
      the bridge logs and drops it; the order stays `cancelled`; **stock is not drained although the goods were
      moved**. Count and correct stock by hand (`POST /warehouse/positions/{id}/stock-adjustments`). This is the
      known residual risk; note how long such a window would be in practice (your outage tolerance).

## 8. Restart and disconnect resilience

- [ ] **Restart the central mid-order** (`docker compose restart` on the Infra host while the rover drives):
      the central log shows `Orden ... recuperada`, the rover is commanded again when it reports online, and the
      order completes (or is cancelled by you). The backend order stays `in_progress` throughout. Telemetry
      resumes within about 30 s.
- [ ] **Restart the bridge mid-order**: nothing is lost (messages wait in `wh-backend.vehicles`; unacknowledged
      ones are redelivered; duplicates are harmless).
- [ ] **Restart the backend mid-order** (`$PROD restart backend`): bridge retries with backoff and delivers the
      pending statuses afterwards (`bridge` log `retrying`).
- [ ] **Power off the rover** (or its Wi-Fi) mid-order: telemetry `error` with `CONNECTION_LOST`, the order stays
      `in_progress`, the console shows the `vehicle.error` event; switch it on again: the run resumes.
- [ ] **Cancel while the rover is disconnected**: backend `cancelled` at once; when the rover comes back the central
      reverses it and reports `idle`.

## 9. Vehicle stuck detection (no automatic release exists)

After every scenario above, run:

```sh
curl -s "$H/vehicles?size=50" -H "Authorization: Bearer $TOKEN" \
 | jq --arg now "$(date -u +%FT%TZ)" '.vehicles[] | select(.status=="busy" and .current_order_id==null) | {id,name,status,last_seen_at}'
```

- [ ] Empty output once the rover is at rest. A vehicle listed here for more than 2 minutes (the central reports
      about every 30 s) is stuck `busy` without an order: check the central status page for the vehicle; an `idle`
      reading frees it. Note any occurrence: it decides whether an alert/timeout is worth building.

## 10. Final checks

- [ ] No order left `in_progress`: `curl -s "$H/orders?status=in_progress" ... | jq '.pagination.total_elements'` is `0`
      (cancel leftovers; staging orders carry destination `STAGING`).
- [ ] Stock of the products used is back to the physical counts (`reserved` 0). Correct by adjustment if needed.
- [ ] Vehicle `idle`, rover at its start position, central status page clean.
- [ ] Logs saved: bridge, backend, central events (status page JSON), for the evidence folder.

## 11. Rollback

- Backend problem: `WH_IMAGE_TAG=<previous tag>` in `.env`, `make deploy` (`docs/OPERATIONS.md`, "Deploy and rollback").
  Orders stay in MongoDB; Kafka keeps the `orders` messages.
- Stop talking to Infra without touching the rest: `$PROD rm -sf bridge`. Orders still get created and queue
  in Kafka (the offset is committed only after RabbitMQ confirms); start the bridge again to resume. Cancel
  anything created during the incident through `POST /orders/<id>/cancel`.
- Rover misbehaving: emergency stop, then power it off. Cancel the order in the backend (immediate), reset the
  rover by hand to its start, and have the Infra team clear the run in their database before powering it on
  (otherwise the central resumes the interrupted order on reconnection).
- Dirty data after the window: cancel remaining staging orders, reconcile stock with adjustments, no manual Mongo edits.

## Sign-off

| Section | Result (ok / issue) | Evidence | Who / when |
|---|---|---|---|
| 1 Plumbing | | | |
| 3 Happy path | | | |
| 5 Cancel in progress (+ variants) | | | |
| 6 Duplicates | | | |
| 7 Lost cancel | | | |
| 8 Restarts | | | |
| 9 Stuck detection | | | |
