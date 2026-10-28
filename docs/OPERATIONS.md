# Operations

What to check on a real server, and what to do when something looks wrong. Complements
`docs/CONSOLE.md` (telemetry, Caddy) and `bridge/README.md` (the Infra bridge).

## First deploy checklist

1. **Checkout.** `/opt/wh/wh-backend` must be on the release you deploy, with the current layout
   (`git fetch --tags && git reset --hard <tag-or-sha>`). The Deploy workflow does this itself; a
   manual deploy on a checkout stuck on an old tag runs an old Makefile against new images.
2. **`.env`** (copy `.env.example`). Required: `SPRING_DATA_MONGODB_URI`, `JWT_SECRET` (32+ chars),
   `MONGO_ROOT_USER`, `MONGO_ROOT_PASSWORD`. `make deploy` runs `scripts/prod-env.sh check` first
   and stops with the list of what is missing, before pulling or restarting anything.
3. **Infra (optional).** Set both `INFRA_AMQP_URL` and `INFRA_API_KEY`, or neither:
   - `INFRA_API_KEY` is the secret the backend checks on `/infra/**` and the bridge sends; the same
     value must reach both. Generate one with `make infra-key`. Unset, the backend rejects every
     `/infra/**` call (and logs a warning at startup).
   - `INFRA_AMQP_URL` is `amqp://user:pass@host:5672/`: RabbitMQ's **AMQP port 5672**, not the MQTT
     plugin's 1883. Pointing at 1883 shows up as `dial rabbitmq: Exception (501) Reason: "EOF"`;
     the check refuses it and the bridge logs a hint.
   - Without both, `make deploy` does not start the bridge (it used to crash-loop with
     `exit 2` and `restart: always`) and says so. Orders then never reach Infra, and the console's status page
     shows `bridge` as down (expected: there is none).
4. **Image tag.** `WH_IMAGE_TAG` pins backend, bridge and console to one release. Unset means
   `latest`. See "Deploy and rollback".
5. **Caddy**, once, by the proxy's owner: `/api/*` (both variants), `/console/*`, metrics listener,
   admin on `localhost:2020` (`docs/CONSOLE.md`, "Caddy").
6. **`make deploy`**, then check `docker compose ... ps`, that the backend logged
   `Started ...Application`, and, with Infra, that `wh-backend-bridge-1` is `healthy`.
7. **QR codes.** Every position that holds stock needs a `qr_code` that matches the printed code
   (next section). Verify that none is null with the check below.
8. **One test order** through `POST /orders`; its published message must carry non-empty `stops`.

## Deploy and rollback

`make deploy` pulls exactly the images the prod overlay runs, pinned by `WH_IMAGE_TAG`:
`wh-backend`, `wh-console` and, when Infra is configured, `wh-bridge`. Then it recreates the
telemetry services and the apps (order in `docs/CONSOLE.md`). Data volumes and MongoDB/Redpanda
are never touched.

```sh
# Pin a pre-release or a release in the server's .env:
WH_IMAGE_TAG=beta-20261020-1234abc     # or v1.4.0
make deploy

# Roll back: put the previous tag back and deploy again (remove the line to return to latest).
WH_IMAGE_TAG=v1.4.0
make deploy
```

If compose or config changed between releases, also check the checkout out at the matching tag
(`git reset --hard <tag>`) before `make deploy`; the Makefile and the overlay come from it.

## Orders and QR codes

An order's `stops` are the `qr_code` texts of the positions the stock will be taken from, once
each, in warehouse traversal order (zone code, line number, position name): `StopResolver`. The
backend publishes them to Infra (through the bridge) with the order.

**An order with empty `stops` is cancelled immediately by Infra.** `POST /orders` still returns
success, because the backend cannot know; the order simply comes back cancelled. A position that
holds stock but has no `qr_code` is left out of `stops` (the backend logs
`Position ... has no QR code`), so if every position involved lacks one, `stops` is `[]`.

The warehouse has ONE zone (`A`) with ONE line (`1`) and SIX positions: `PA1`, `PA2`, `PA3`, `PM1`,
`PM2`, `PM3` (the zone is created with `max_allowed_lines` = 1 and the line with
`max_allowed_positions` = 6; these limits describe the seed and are informational: the backend does not enforce them). Their names are the QR codes the Infra vehicle recognises, and the printed texts are
`WH2|PA1` ... `WH2|PA3` and `WH2|PM1` ... `WH2|PM3`: the prefix `WH_QR_PREFIX` (default `WH2|`)
followed by the position name. The backend creates exactly this on the first boot of an empty
database (`DataInitializer`, six empty positions), with or without `SEED_DEMO`; the demo seed then
fills the same six positions with products and stock instead of adding more. So **a freshly seeded
server already carries the codes Infra knows and nothing needs reassigning**. The texts printed in
the real warehouse and known by Infra remain the source of truth: if they ever differ (another
prefix, a renamed position), set `WH_QR_PREFIX` before the first boot or reassign as below. Seeding
never rewrites an existing database: on a server that is already seeded, the codes stay as they are
until you reassign them (an older server seeded with zones `B`/`C` keeps `WH2|PB1`..., and
`SEED_DEMO` skips with a warning when it finds zones other than `A`).

The demo seed covers two years, daily, for every business metric (list and sources in
`docs/SEEDED_METRICS.md`); the backend logs `Demo seed took N ms` when it finishes. It then publishes
one `order.completed` per seeded completed order so the suggestions service starts with the same
orders (about 1 s to publish, a few seconds for the service to ingest them; see
`docs/SUGGESTIONS.md`, "Preload from the demo seed", for the Redis-wipe, group-reset and
retention procedures). If Kafka is down at that moment the backend only logs a warning and the
suggestions model starts empty.

### Checking the codes

The flat `GET /warehouse/positions` does not carry `qr_code`; `GET /warehouse/positions/{id}` and
`GET /warehouse/lines/{lineId}/positions` do:

```sh
for id in $(curl -fsS "$H/warehouse/positions" -H "Authorization: Bearer $TOKEN" | jq -r '.positions[].id_position'); do
  curl -fsS "$H/warehouse/positions/$id" -H "Authorization: Bearer $TOKEN" \
    | jq -r '[.id_position, .product_id // "-", .qr_code // "MISSING"] | @tsv'
done        # no MISSING on a position that holds stock
```

### Assigning QR codes in bulk

`PATCH /warehouse/positions/{id}` (roles `SUPERADMIN`, `ADMIN_WAREHOUSE`) with the field in
snake_case:

```sh
curl -X PATCH "$H/warehouse/positions/$ID" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"qr_code":"WH2|PA1"}'
```

- The JSON field is `qr_code`. **`qrCode` (camelCase) is silently ignored** and the call returns
  200 without changing anything.
- Codes are unique: another position with the same text answers `409 QR_CODE_ALREADY_EXISTS`.
- A blank string (`""`) clears the code; the value is trimmed.
- Swapping codes between positions fails halfway with that same 409 (the code is still held by the
  other one): blank the codes first (`{"qr_code":""}`), or move through a temporary text.

Whole warehouse in one go (only needed on a server not seeded with the current layout, or after
changing the prefix): `GET /warehouse/positions` lists every position (`id_position`, `zone_code`,
`number_line`, `position_name`, ordered by zone, line, position). Build a two-column file
`<id_position><TAB><qr_code>`, edit it to the codes Infra knows, and apply it:

```sh
H=https://<public-host>/api            # whatever the proxy exposes (docs/CONSOLE.md, Caddy)
TOKEN=...                              # POST $H/auth/login as SUPERADMIN or ADMIN_WAREHOUSE

# 1. Start from the current scheme: <prefix><position name> (WH2|PA1 ... WH2|PM3).
curl -fsS "$H/warehouse/positions" -H "Authorization: Bearer $TOKEN" \
  | jq -r '.positions[] | [.id_position, "WH2|\(.position_name)"] | @tsv' > positions.tsv

# 2. Edit positions.tsv if Infra's texts differ from that, then apply:
while IFS=$'\t' read -r id qr; do
  curl -sS -X PATCH "$H/warehouse/positions/$id" -H "Authorization: Bearer $TOKEN" \
    -H 'Content-Type: application/json' -d "$(jq -nc --arg q "$qr" '{qr_code:$q}')" \
    -o /dev/null -w "$id $qr -> %{http_code}\n"
done < positions.tsv        # every line must end in 200
```

### Capacity guard gotcha

`PATCH /warehouse/positions/{id}` re-validates the position on **every** call, even if you only
send `qr_code`: `current_stock` must not exceed `maximum_capacity`, nor
`current_stock x product volume` the volume of `size_stock_to_save`
(`CAJA` 48,000 cm3, `MEDIO_PALLET` 900,000, `PALLET` 1,800,000; volume is height x width x length
in cm). A position that already violates it answers `409 STOCK_EXCEEDS_CAPACITY` for any edit. Fix it
in the same request (lower `current_stock`, or a larger `size_stock_to_save`), or correct the
product's dimensions. The demo seed satisfies these rules (`DemoDatasetTest`).

### What to do if orders get cancelled immediately

1. Look at the `stops` of the message published for the order (RabbitMQ, or the bridge log).
   Empty `stops` is the usual cause.
2. List the codes (see the check below) and find the positions of the ordered products: `qr_code`
   null or blank, or different from what Infra knows. Assign them as above.
3. If `stops` are present, check the bridge: `docker logs wh-backend-bridge-1` (AMQP errors,
   `Exception (501)` means the wrong port) and that `INFRA_API_KEY` is the same on both sides
   (the backend answers `401 INVALID_API_KEY` otherwise).
4. Stops are resolved from positions that hold stock of the ordered products; a product with no
   stock in any position yields none.

## Sessions and MongoDB timeouts

**Deactivated users.** A JWT is only accepted while its user exists and is active. The check is
cached per backend instance for `JWT_USER_STATUS_CACHE_TTL_MS` (property `jwt.user-status-cache-ttl-ms`,
default 15000; `0` checks on every request) and the cache entry is dropped at once when the user is
updated through the API on the same instance. Applies to the REST API, the WebSocket handshakes and
the console; `/infra/**` keeps its API key. Residual windows: another backend instance, and the
suggestions service (which verifies JWTs on its own with the shared secret and never consults the
user store), keep accepting a deactivated user's token until the cache TTL / the token expires
(`JWT_EXPIRATION_MS`, 24 h by default). Connections already open (WebSocket, console stream) are not
closed on deactivation. A role change is not reflected in tokens already issued, as before. If the
user store cannot be read, the last known answer is used (a signed token with no known answer is
accepted; the request then fails on its own database access).

**MongoDB timeouts.** A call no longer waits 30 s for a database that is down: it answers
`503 DATABASE_UNAVAILABLE` (`{"error": {"code", "message"}}`) after the server selection timeout and
`/actuator/health` reports `DOWN`. Tunables (milliseconds): `WH_MONGO_SERVER_SELECTION_TIMEOUT_MS`
(3000), `WH_MONGO_CONNECT_TIMEOUT_MS` (3000), `WH_MONGO_SOCKET_TIMEOUT_MS` (30000, `0` = none).
