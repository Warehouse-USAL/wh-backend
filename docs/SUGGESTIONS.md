# Suggestions service

`suggestions/` is a Go service that turns the order history into product recommendations: "also add"
for a cart, "what similar users bought" for a user, and the SKUs trending right now. It implements
RFC [wh-general#12](https://github.com/Warehouse-USAL/wh-general/issues/12) and supersedes the
standalone `wh-suggestions` scaffold repository.

```
wh-backend ──Kafka `order.completed`──► suggestions ──► Redis (its only store, AOF)
                                             ▲
              web / apps ──HTTP + JWT────────┘   /v1/recommendations/*, /healthz
```

The backend is untouched: it already publishes `order.completed` (`OrderCompletedMessage`,
camelCase) when an order completes, seeded or real. The service never calls the backend; JWTs are
verified locally.

## API

Every `/v1` route needs `Authorization: Bearer <JWT issued by wh-backend>`. Responses:

```json
{"recommendations": [{"sku": "TEC-004", "score": 12.0}]}
```

| Route | Purpose | Auth |
|---|---|---|
| `GET /v1/recommendations/cart?skus=TEC-002,TEC-004&limit=5` | "Also add", idempotent and cache-friendly (the one the web uses). `skus` is comma separated and may repeat. | any valid JWT |
| `POST /v1/recommendations/cart` body `{"skus":[...],"limit":5}` | Same logic and response; for lists too long for a URL. `limit` optional. | any valid JWT |
| `GET /v1/recommendations/user/:userId?limit=5` | SKUs bought by users similar to `:userId` (see below). | JWT `sub` == `:userId`, or role `SUPERADMIN` / `ADMIN_SYSTEM` (the backend's `SYSTEM_ADMINS`) |
| `GET /v1/recommendations/hot?limit=10` | Top of `hot:global`. | any valid JWT |
| `GET /healthz` | 200 only if Redis answers PING **and** the Kafka loop is alive; 503 otherwise, with `{"status","redis","consumer"}`. | none |

`limit` is an integer in 1..50 (defaults: 5, hot 10). A cart takes 1..100 distinct SKUs of at most 64
characters. Errors always look like `{"error": "<code>", "message": "<text>"}`:

| Status | `error` | When |
|---|---|---|
| 400 | `bad_request` | bad `limit`, empty/oversized/invalid `skus`, malformed body |
| 401 | `unauthorized` | missing, malformed, badly signed or expired token |
| 403 | `forbidden` | `/user/:userId` of someone else without a system-admin role |
| 404 / 405 | `not_found` / `method_not_allowed` | unknown route / method |
| 500 | `internal_error` | Redis failure |

CORS: the service sends no CORS headers; browsers reach it same-origin through Caddy (`/suggestions/*`), and server-side clients are unaffected. The HTTP server has read/write/idle timeouts and a 16 KiB header limit; cart bodies are capped at 64 KiB.

JWT: the backend's `JwtService` signs with `Keys.hmacShaKeyFor(JWT_SECRET)` and claims `sub` (user
id), `email`, `role`, `exp`. jjwt picks HS256, HS384 or HS512 from the secret's length, so the
service accepts those three HMAC algorithms (and nothing else) with the same secret.

## Redis model

| Key | Type | Content | Written per event |
|---|---|---|---|
| `user:{userId}:skus` | zset | sku -> total quantity the user ordered | `ZINCRBY qty` per distinct SKU |
| `sku:{sku}:buyers` | zset | userId -> total quantity (inverted index of the above; **added** to answer the RFC comment) | `ZINCRBY qty` per distinct SKU |
| `cooccur:{sku}` | zset | other sku -> number of orders containing both | `ZINCRBY 1` for each ordered pair, both directions |
| `hot:global` | zset | sku -> decayed quantity | `ZINCRBY qty` per distinct SKU |

| `processed:{orderId}` | string | marker `1` of an order already applied, with a TTL (`ORDER_DEDUPE_TTL`) | `SET ... EX`, in the same atomic step as the counts |

One event is applied atomically (a single Lua `EVAL`, or `MULTI/EXEC` for events without an order id). Lines repeating a SKU are summed; lines without a SKU
or with quantity <= 0 are ignored; a line counts at most 1000 (so a corrupt quantity cannot pin a SKU on top); an order without `userId` still feeds `hot:global` and
`cooccur`.

Delivery is at-least-once, so ingestion is idempotent per order id: the script skips the event when
`processed:{orderId}` exists and otherwise applies the counts and then sets the marker, all in one
atomic step. A redelivered `order.completed` (consumer retry, rebalance, a crash between the Redis
write and the offset commit) is therefore counted once, and the marker never exists without its
counts. The marker expires after `ORDER_DEDUPE_TTL` (default `168h`, 7 days, the default Kafka
retention, so a replay can not be older), which bounds memory to about one small key per order of
the last week. A redelivery later than the window would be counted again. Events without an
`orderId` cannot be recognised and are applied every time. The id is only ever used behind the
fixed `processed:` prefix, like the other key builders in `store/keys.go`.

## Decisions on the RFC comment

**1. The user endpoint is collaborative filtering, not "buy again".** It recommends SKUs bought by
users who share purchases with the user, never SKUs the user already bought. Algorithm (all reads
bounded, three Redis round trips):

1. Read the user's whole history from `user:{id}:skus` (it is also the exclusion set); the
   `ProfileTop = 50` SKUs with the most quantity form the profile.
2. For each profile SKU read its top `BuyersTop = 200` buyers from `sku:{sku}:buyers` and its
   `ZCARD n`. Every other buyer `v` earns `1/(1+n)` per shared SKU (inverse user frequency: sharing
   a niche SKU says more than sharing a bestseller). Keep the `Neighbours = 50` heaviest.
3. For each neighbour read its top `NeighbourTop = 50` SKUs. `score(sku) = sum over neighbours of
   weight(v) * ln(1 + quantity(v, sku))`, skipping owned SKUs. Return the top `limit`.

Cold start: a user without history gets `hot:global`. A user whose neighbours bought nothing new
gets `hot:global` minus what they own. Without the inverted index, finding neighbours would need a
scan of every `user:*:skus` key; with it the cost is independent of the number of users.

**2. The cart endpoint exists as GET and POST.** GET is the idempotent, cacheable form for the web;
POST (the RFC's form) stays for long lists. Both call the same function. Logic: sum `cooccur:{sku}`
(top 100 per SKU) over the cart, drop the cart's own SKUs, return the top `limit`; with no
co-occurrence it falls back to `hot:global` minus the cart.

## Hot items and decay

A goroutine runs every `HOT_DECAY_INTERVAL` (15 min) a Lua script (`EVAL`) that multiplies every
`hot:global` score by `HOT_DECAY_FACTOR` (0.97) and removes members that fall below 0.01 so the set
stays bounded. That is exponential decay with a half-life of about 5.7 hours. `user:*`, `buyers` and
`cooccur` do not decay on purpose: they describe taste, not trend. The factor is an untested guess
(RFC section 10); revisit with real traffic.

## Durability

Redis holds the whole model and runs with AOF (`--appendonly yes`) on a volume (named volume in prod,
`./data/suggestions-redis` in dev). The consumer group `wh-suggestions` starts from the earliest
offset, so a fresh group (or an emptied Redis plus a new `KAFKA_GROUP_ID`) rebuilds the model by
replaying the topic as far as Redpanda's retention allows. Offsets are committed only after Redis
accepted the event; while Redis is down the same message is retried with backoff. During a deploy the
group rebalances and ingestion pauses briefly (acceptable, no correctness requirement).

## Backtest

`suggestions/internal/recommend/backtest_test.go` generates synthetic events in the test (fixed
seed; the backend's demo seeder is not involved), feeds them through the real ingest code into
miniredis and scores precision@3 against held-out purchases, versus a "always return hot items"
baseline:

- five pairs always ordered together: cart recommendations for one half must contain the other;
- a group of ten users with shared niche taste, each missing one SKU the others bought: the user
  endpoint must surface it;
- a trending SKU after a long decayed period of an old star: it must lead `hot`.

Both recommenders reach 0.333 (the maximum with one held-out SKU and k=3) versus 0.000 for the
baseline.

## Running

Dev (needs the base stack for Redpanda; the service is opt-in):

```
docker compose --profile suggestions up --build suggestions
curl -H "Authorization: Bearer $TOKEN" 'localhost:8084/v1/recommendations/hot'
```

Tests: `make suggestions-test` (`go vet` + `go test -race`, no Docker needed).

Prod: `docker-compose.prod.yml` defines `suggestions` (image
`ghcr.io/warehouse-usal/wh-suggestions:${WH_IMAGE_TAG:-latest}`, on `wh-network` and `wh-proxy`,
listening on `:8080`) and `suggestions-redis`. `make deploy` pulls and restarts them with the other
images. Caddy (wh-autodeploys) already routes `/suggestions/*`, prefix stripped, to `suggestions:8080`.

## Environment

| Variable | Default | Description |
|---|---|---|
| `JWT_SECRET` | required, >= 32 chars | The backend's secret (same `.env` variable) |
| `KAFKA_BROKERS` | `redpanda:9092` | Comma separated (compose passes `REDPANDA_BOOTSTRAP_SERVERS`) |
| `KAFKA_TOPIC` | `order.completed` | |
| `KAFKA_GROUP_ID` | `wh-suggestions` | |
| `REDIS_ADDR` | `suggestions-redis:6379` | |
| `REDIS_PASSWORD` | empty | |
| `HTTP_ADDR` | `:8080` | Also used by the container healthcheck (`/suggestions healthcheck`) |
| `HOT_DECAY_FACTOR` | `0.97` | In (0, 1) |
| `HOT_DECAY_INTERVAL` | `15m` | |
| `ORDER_DEDUPE_TTL` | `168h` | How long an applied order id is remembered to ignore redelivered events; positive duration |

Not done: OpenTelemetry metrics (the bridge exports them; the RFC does not ask for it), per-zone hot
items and the warehouse-side restock suggestions (RFC out of scope).
