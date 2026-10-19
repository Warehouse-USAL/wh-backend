# Backend console

SUPERADMIN-only console for the backend, served at `/console/`. This file records what the
platform side needs and what was verified against a running stack.

## Run locally

```sh
cp .env.example .env            # ADMIN_EMAIL / ADMIN_PASSWORD become the bootstrap SUPERADMIN
docker compose --profile dev up -d --build
open http://localhost:8083/console/
```

Sign in with the SUPERADMIN account (`ADMIN_EMAIL` / `ADMIN_PASSWORD`, or `admin@smartwarehouse.local`
/ `Demo1234!` with `SEED_DEMO=true` on a fresh database, see `docs/demo-credentials.md`). Any other
role is refused. For console development, `cd console && npm ci && npm run dev` serves
`http://localhost:5173/console/` and proxies `/api` to `localhost:8080` (`console/README.md`).

## Overview

| Service | Port | Notes |
|---|---|---|
| `console` | host `8083:80` (dev only) | nginx serving `console/`; in prod `ghcr.io/warehouse-usal/wh-console:${WH_IMAGE_TAG:-latest}` on `wh-network` + `wh-proxy`, no host port, reached through Caddy at `/console/`. Healthcheck: `wget` on `/console/` |
| console dev-tools proxies | host `8091` (mongo-express), `8092` (redpanda-console), dev only | frame-safe proxies (`console/nginx.dev-tools.conf`, mounted only by `docker-compose.yml`); the tools themselves run only under `--profile dev` (host `8081`, `8082`). Prod mounts nothing, so neither port is served there |
| `victorialogs` | `9428` internal | `victoriametrics/victoria-logs:v1.53.0`, named volume `victorialogs_data`, `-retentionPeriod=30d` |
| `otel-collector` | `4317`/`4318` (dev host only), `13133` health, `8888` own metrics (localhost) | metrics pipeline `[otlp, prometheus]` → VictoriaMetrics; logs pipeline `[otlp]` → VictoriaLogs. Prod adds `collector-config.prod.yml` (Caddy scrape) and joins `wh-proxy` |

Environment variables the console and its telemetry use. The default is the code's when the
variable is unset; "compose" marks values the compose files set. The backend image ships no
`application.yml`, so these env vars (and the code defaults) are its whole configuration.

| Variable | Service | Default | Meaning |
|---|---|---|---|
| `TELEMETRY_ENABLED` | backend | `false`; compose `true` | master switch for metric **and** log export. `false` = nothing exported, the backend runs normally |
| `TELEMETRY_ENDPOINT` | backend | `http://otel-collector:4318/v1/metrics` | OTLP metrics |
| `TELEMETRY_LOGS_ENDPOINT` | backend | `http://otel-collector:4318/v1/logs` | OTLP logs (the Logs page's source) |
| `TELEMETRY_EXPORT_INTERVAL_MS` | backend | `10000` | metric export period; bounds how fresh every backend chart is |
| `TELEMETRY_EXPORT_TIMEOUT_MS` | backend | `5000` | per export |
| `TELEMETRY_MONGO_SLOW_MS` | backend | `100` | `mongo.command.duration{outcome="slow"}` threshold |
| `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` | backend | compose `true` | Tomcat MBeans; without it the `tomcat.*` series are absent |
| `VICTORIAMETRICS_URL` | backend | `http://victoriametrics:8428` | read side of the metrics (`/metrics/query`, the stream) |
| `CONSOLE_VICTORIALOGS_URL` | backend | `http://victorialogs:9428` | read side of the logs |
| `CONSOLE_BRIDGE_URL` | backend | `http://bridge:8081` | status probe (blank = `UNKNOWN`) |
| `CONSOLE_MINIO_URL` | backend | `http://minio:9000` | status probe |
| `CONSOLE_VICTORIAMETRICS_URL` | backend | `http://victoriametrics:8428` | status probe |
| `CONSOLE_COLLECTOR_URL` | backend | `http://otel-collector:13133` | status probe (collector health extension) |
| `CONSOLE_STREAM_*` | backend | the `console.stream.` table below | e.g. `CONSOLE_STREAM_MAX_STREAMS=50`, `CONSOLE_STREAM_HEARTBEAT=15s` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | bridge | `http://otel-collector:4318` | blank = no export |
| `MINIO_PROMETHEUS_AUTH_TYPE` | minio | `public` | collector scrapes `/minio/v2/metrics/cluster` without a token; anyone reaching port 9000, host-published in prod too, can read these counts and sizes |
| `RESTOCK_OTLP_URL` | restock-cron | `http://otel-collector:4318/v1/metrics` | `job.run` push; blank = no push |
| `RESTOCK_SCHEDULE` | restock-cron | `0 6 * * *` | crond expression in **UTC** (the image has no tzdata; do not set `TZ`). The console's "Next run" tile assumes this default |
| `WH_IMAGE_TAG` | prod overlay | `latest` | GHCR tag of `wh-backend`, `wh-bridge` and `wh-console` (a release tags all three) |

Scrape jobs (`job` label = job name, every 30s): `otel-collector` (`localhost:8888`),
`victoriametrics` (`victoriametrics:8428`), `minio` (`minio:9000/minio/v2/metrics/cluster`), and
in prod only `caddy` (`caddy:2019/metrics`, every 60s). A down target only sets `up{job=...}=0`
and logs one `Failed to scrape Prometheus endpoint` warning per interval; the other jobs and OTLP
keep flowing. The `victoriametrics`, `minio` and `otel-collector` jobs keep only the series listed
under "Scraped series" (`metric_relabel_configs`): VictoriaMetrics retains 800 days, so every
unused series costs disk for that long. Adding a chart means adding its series to the keep-list.

restock-cron pushes `job.run` (OTLP/JSON gauge) after each run, twice: `field=duration_ms` and
`field=suggestions` (rows in the apply response; `0` when the run fails), both with `job=restock`
and `result=ok|error`. The startup run counts once: its `duration_ms` spans every attempt, retry
waits included. The push has a 5 s cap
and never changes the run's exit status. The payload has no `service.name` on purpose: the
remote-write exporter would map it onto the `job` label and overwrite `job=restock`.

**Accepted risk (prod):** the collector joins `wh-proxy` to scrape Caddy, and its OTLP receivers
(4317/4318) and health check (13133) listen on `0.0.0.0`. Any container on the shared proxy network
can therefore write metrics and logs into VictoriaMetrics and VictoriaLogs (it cannot read them:
neither store is on `wh-proxy`). Accepted for now; the fix would be a dedicated scrape-only network
shared with Caddy.

## Production rollout

No data migration: nothing in MongoDB changes. The only new persistent state is the
`victorialogs_data` volume (30 days of logs, disposable) and new series in VictoriaMetrics.

### Before the first deploy

1. `.env` on the server (`/opt/wh/wh-backend/.env`): `JWT_SECRET` (at least 32 random chars),
   `SPRING_DATA_MONGODB_URI`, `MONGO_ROOT_USER` and `MONGO_ROOT_PASSWORD` must be set; the prod
   overlay refuses to start without them. `ADMIN_EMAIL` / `ADMIN_PASSWORD` create the first
   SUPERADMIN only when none exists yet (`DataInitializer`); an existing SUPERADMIN keeps its
   password. That account is the console login. `MINIO_ACCESS_KEY` / `MINIO_SECRET_KEY` still fall
   back to `minioadmin` when unset: set them. Leave `COMPOSE_PROFILES` unset (the `dev` profile
   would start mongo-express without auth and redpanda-console).
2. Images: the Stable Release workflow publishes `ghcr.io/warehouse-usal/wh-console:<version>` and
   `:latest` next to the backend and bridge. Its first push creates the package; check on the
   server that it pulls with the deploy credentials before the first deploy (otherwise give the
   package the same visibility and Actions access as `wh-bridge`):

   ```sh
   docker pull ghcr.io/warehouse-usal/wh-console:latest
   ```

3. The `wh-proxy` network already exists (the backend uses it); the console and the collector
   join it.

### Deploy order

`make deploy` (run by the Deploy workflow) does these steps on every deploy, in this order:

```sh
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull backend bridge console
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps victorialogs
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps --force-recreate otel-collector
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps minio victoriametrics
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps backend bridge console restock-cron
```

- `--no-deps` everywhere: MongoDB, its init containers and Redpanda are never recreated by a
  deploy. The others are recreated only when their compose config or image changed.
- MinIO is recreated once for `MINIO_PROMETHEUS_AUTH_TYPE`, so it restarts briefly (uploads fail
  for a few seconds).
- The collector config is a bind mount: when only `collector-config*.yml` changed, compose sees no
  change, hence `--force-recreate` (the collector restarts on every deploy; a few seconds of
  telemetry are lost, the senders retry).
- Order: the collector must have the logs pipeline before the new backend and bridge are deployed;
  otherwise their log export gets 404s and warns on stdout until it does.
- The Deploy workflow then waits for the backend's start line, the bridge's healthcheck, and the
  console's healthcheck plus one request through its `/api` proxy.

### Caddy (once, by the proxy's owner)

Apply "Caddy (manual step, shared proxy)" below: the `/console/*` route, `metrics { per_host }`,
the admin API moved to `localhost:2020` (first reload with `--address localhost:2019`), the
`:2019 { metrics }` listener the collector scrapes on `wh-proxy`, and `flush_interval -1` on the
route to the backend ("Push stream"). Until then the console is unreachable from outside and the
Edge page has no data; nothing else is affected, so it can come before or after the deploy.

### Verify

```sh
cd /opt/wh/wh-backend
C="docker compose -f docker-compose.yml -f docker-compose.prod.yml"
$C ps console victorialogs otel-collector victoriametrics     # console "healthy", the rest "Up"
docker exec wh-backend-console-1 wget -qO- http://127.0.0.1/api/actuator/health   # {"status":"UP"...}

# Through Caddy, as the SUPERADMIN (the password is read, never put in the shell history):
H=https://<public-host>
read -rs P
TOKEN=$(jq -nc --arg e "$ADMIN_EMAIL" --arg p "$P" '{email:$e,password:$p}' \
  | curl -fsS "$H/api/auth/login" -H 'Content-Type: application/json' -d @- | jq -r .token); unset P
curl -sS -o /dev/null -w '%{http_code}\n' "$H/console/"                          # 200
curl -fsS -H "Authorization: Bearer $TOKEN" "$H/api/console/status" | jq -c '.services[] | [.name, .state]'
curl -fsS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "$H/api/console/logs/query" -d '{"service":"backend","limit":5}' | jq .total   # > 0 after a minute
curl -sSN -m 20 -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  "$H/api/console/stream" -d '{"subscriptions":[{"id":"s","kind":"status"}]}'    # "event: snapshot" at once
unset TOKEN
```

A non-SUPERADMIN token gets `403` from every `/console/*` endpoint (`@PreAuthorize` on each
controller), and the console UI refuses it at login. A stream whose snapshot shows up only after
many seconds is being buffered by a proxy.

## Caddy (manual step, shared proxy)

The Caddyfile lives with the shared proxy, outside this repo. Until its owner applies the
following, `/console/` does not route and the Edge page has no data (`up{job="caddy"}=0`).
Verified with `caddy:2.10.2`.

1. Global options: enable HTTP metrics per host. Keep the admin API off the shared network (it
   accepts config changes) by moving it off port 2019:

   ```caddyfile
   {
   	admin localhost:2020
   	metrics {
   		per_host
   	}
   }
   ```

2. A metrics-only listener on `wh-proxy` (do not publish it on the host):

   ```caddyfile
   :2019 {
   	metrics
   }
   ```

3. In the existing site block, next to the `/api/*` route:

   ```caddyfile
   redir /console /console/ 308
   handle /console/* {
   	reverse_proxy console:80
   }
   ```

   Use `handle`, not `handle_path`: the console image serves its files under `/console/`, so the
   prefix must reach it (stripping it sends `/` back to `/console/` in a loop).

Verify from the collector's network (the collector image is distroless, so use a curl container):

```sh
docker run --rm --network wh-proxy curlimages/curl:8.11.1 -s caddy:2019/metrics | grep -m1 '^caddy_'
```

Then `up{job="caddy"}` turns 1 within a minute.

Moving the admin address: the running Caddy still listens on the old one, so the first apply must
target it, `caddy reload --config /etc/caddy/Caddyfile --address localhost:2019` (or restart the
Caddy container). Later reloads read `localhost:2020` from the Caddyfile.

## Push stream (`POST /console/stream`)

The browser never polls: each open console view holds one Server-Sent Events response that
carries all its live data. SUPERADMIN only. The JWT goes in the `Authorization` header as for every
call (a token in the URL is ignored), so the console reads it with `fetch`, not `EventSource`.

Request (`Content-Type: application/json`), at most 200 subscriptions, ids unique and matching
`[A-Za-z0-9_.-]{1,64}`:

```json
{"subscriptions": [
  {"id": "p95", "kind": "metric", "metric": "http.server.duration", "range": "15m", "step": "10s",
   "agg": "p95", "group_by": ["route"], "filters": {"method": "GET"}},
  {"id": "errors", "kind": "logs", "service": "backend", "level": "error", "range": "1h", "limit": 100},
  {"id": "orders", "kind": "ws-events", "feed": "orders"},
  {"id": "status", "kind": "status"}
]}
```

| Kind | Params | Payload (`data`) |
|---|---|---|
| `metric` | as `POST /metrics/query`, but `range` (`5m`, `15m`, `1h`, `6h`, `24h`) instead of `from`/`to`. The server uses the console's window: `to = floor(now/step)*step`, `from = to - range + step` | the `/metrics/query` response plus `window: {from, to, step}` (ISO instants of the first and last bucket), so the client lays series onto the server's grid; `mode: "replace"` |
| `logs` | as `POST /console/logs/query` (same validation and limit cap), plus `range` | snapshot: the REST response (newest first); then only new lines, oldest first, never repeated; `mode: "append"` |
| `ws-events` | `feed`: `orders`, `user-orders`, `vehicles`, `stock-alerts` | snapshot: the `/console/ws-events/{feed}` response; then each event as it happens; `mode: "append"` |
| `status` | none | the `/console/status` response, again only when a state or detail changes (the backend `uptime_ms` alone does not count); `mode: "replace"` |

Response headers: `Content-Type: text/event-stream`, `Cache-Control: no-cache`,
`X-Accel-Buffering: no`. Frames (each `data` is one line of JSON):

```
event: snapshot
data: {"id":"orders","mode":"append","data":{"feed":"/ws/v1/orders","events":[...]}}

event: update
data: {"id":"status","mode":"replace","data":{"dev":true,"services":[...]}}

event: error
data: {"id":"p95","code":"UNKNOWN_METRIC","message":"La suscripción no es válida."}

: ping

event: end
data: {"reason":"token-expired"}
```

- `snapshot` is the first data frame of a subscription; later ones are `update`.
- `error`: a 4xx from the source (unknown or hidden metric, undeclared dimension) is permanent and
  the source stops querying; anything else (`METRICS_UNAVAILABLE`, `LOG_STORE_UNAVAILABLE`,
  `SOURCE_UNAVAILABLE`) is retried on the next evaluation, and the next good result is pushed even
  if it equals the last one. The message is fixed per class and never names the metric.
- `end` then the response completes: `token-expired` (the stream never outlives its JWT),
  `server-shutdown`, or `limit` (the client fell too far behind). The client reconnects and gets
  fresh snapshots.
- Rejected before the stream starts, with the usual `{"error":{"code","message"}}`: 400
  `INVALID_SUBSCRIPTION` (bad id, duplicate id, unknown kind, bad range or step, too many),
  `INVALID_FILTER_VALUE` (log filters), `MALFORMED_REQUEST`; 429 `STREAM_LIMIT`.

How the server produces it: sources are shared by canonical params across every stream, start with
their first subscriber and stop with their last. WebSocket events are pushed by a listener on the
event buffer (no timer). Logs follow a cursor: every interval the search is re-run from 10 s
(`logs-overlap`) before the newest line seen, and lines already sent are skipped (VictoriaLogs'
`/select/logsql/tail` was not used: it holds an open response per search and needs its own
streaming client). Metrics are the one internal pull (VictoriaMetrics cannot push): one query per
unique subscription every 5 s, about 12 a minute however many streams share it. Each stream has a
bounded queue; a waiting `replace` frame is overwritten by the newer one, and overflowing `append`
frames end the stream with `limit`. A client that accepts nothing for `send-timeout` is dropped.
A client that closes the connection is noticed on the next write that fails (measured 46 s through
Docker Desktop's port forwarder, i.e. after two to three heartbeats). A client whose network
vanishes without closing (half-open TCP) is only noticed when the kernel stops retransmitting (about
15 minutes on Linux; measured 961 s), when its send buffer fills and `send-timeout` fires, or at
token expiry; until then it holds one of its user's five streams.

| Key (`console.stream.`) | Default | |
|---|---|---|
| `metric-interval` | `5s` | metric re-evaluation (the metrics resolution is 10 s) |
| `logs-interval` | `2s` | logs cursor query |
| `logs-overlap` | `10s` | how far back each logs query reaches for late lines |
| `status-interval` | `5s` | status probes |
| `heartbeat` | `15s` | `: ping` comment |
| `send-timeout` | `30s` | drop a client that stopped reading |
| `max-subscriptions` | `200` | per stream |
| `max-streams-per-principal` | `5` | per user |
| `max-streams` | `50` | whole backend |
| `queue-capacity` | `1000` | frames waiting per stream; keep above `max-subscriptions` |

Observability: `http.server.duration` times only the stream's initial dispatch (the time to start
streaming, never how long it stays open), so started streams and rejections (403 as `UNMATCHED`,
429 as `/console/stream`) are counted like any request. The stream is left out of the access log
(it lasts as long as its token). One INFO line on open and one on close (subscription count, duration, reason,
`request_id`; never the user). `DEBUG` on `com.usal.whbackend.service.console.stream` logs each
source start and stop with the number of active sources.

Proxies must not buffer the response:

- The console's nginx (`console/nginx.conf`) has its own `location = /api/console/stream`:
  `proxy_buffering off`, `proxy_cache off`, `gzip off`, HTTP/1.1 with an empty `Connection`
  header, chunked transfer and `proxy_read_timeout 3600s` (the backend's `X-Accel-Buffering: no`
  asks for the same). It adds no headers of its own, so the server's CSP and security headers still
  apply.

The console's client (`console/src/data/stream/`): one stream for the whole app, opened with
`fetch` and read as a `ReadableStream` (the JWT stays in the header). Widgets subscribe through
hooks (`useMetric`, `useLogs`, `useStatus`, `useWsEvents`), ref-counted by canonical params, so
equal params from several widgets are one subscription. A change to the set is debounced (250 ms)
and applied by reopening the stream with the full set: every source is shared and cached server
side, so the reopen costs one request and the snapshots come from memory. Reconnects use
exponential backoff with jitter (1 s doubling, capped at 30 s; reset by a successful open) and
always re-send the full set; a watchdog reopens the stream after 45 s without a byte (frame or
ping). `401` and `end{token-expired}` end the session and show the login; `400`/`403` stop and show
the error; `429 STREAM_LIMIT`, `5xx`, network errors and other `end` reasons retry. Live off closes
the stream and keeps the data (a subscription added while paused gets one snapshot, then the stream
closes again); Live on reopens it. The header shows the state and the time of the last frame.
- Caddy streams `text/event-stream` as it arrives by default. For the owner of the Caddyfile, the
  explicit safe setting on the route that reaches the backend is:

  ```caddyfile
  reverse_proxy backend:8080 {
  	flush_interval -1
  }
  ```

  Also keep any proxy read timeout above the heartbeat (15 s).

## Scraped series

Exact names as stored in VictoriaMetrics (collector `0.115.1`, MinIO `RELEASE.2026-09-22`,
VictoriaMetrics `v1.111.0`, Caddy `2.10.2`). Counters keep `_total` when the target serves the
classic text format (MinIO, VictoriaMetrics, Caddy) and lose it when it serves OpenMetrics (the
collector's own `otelcol_*`). Every series also carries `job` and `instance`.

**MinIO** (`job="minio"`, every series has `server`)

| Panel | Series | Labels |
|---|---|---|
| Bucket size | `minio_cluster_usage_total_bytes` | — |
| Object count | `minio_cluster_usage_object_total` | — |
| Buckets | `minio_cluster_bucket_total` | — |
| Requests | `minio_s3_requests_total` (counter) | `api` |
| Errors | `minio_s3_requests_errors_by_class_total`: MinIO's `_4xx_errors_total` and `_5xx_errors_total` (`_5xx_` appears once one happens) renamed by `metric_relabel_configs` with `class` = `4xx` or `5xx`; their sum is `minio_s3_requests_errors_total`, not kept | `api`, `class` |
| Rejected | `minio_s3_requests_rejected_auth_total`, `_header_total`, `_invalid_total`, `_timestamp_total` | — |
| Latency | `minio_s3_requests_ttfb_seconds_distribution` (TYPE gauge, cumulative `le` buckets in seconds; use `histogram_quantile` over `rate`) | `api`, `le` |
| Traffic | `minio_s3_traffic_received_bytes`, `minio_s3_traffic_sent_bytes` (counters) | — |
| Capacity | `minio_cluster_capacity_usable_total_bytes`, `minio_cluster_capacity_usable_free_bytes` | — |

Usage values come from MinIO's background scanner, so they lag uploads by minutes.

**Collector** (`job="otel-collector"`, every series has `service_instance_id`, new on each restart)

| Panel | Series | Labels |
|---|---|---|
| Accepted | `otelcol_receiver_accepted_metric_points`, `otelcol_receiver_accepted_log_records` | `receiver`, `transport` |
| Refused | `otelcol_receiver_refused_metric_points`, `otelcol_receiver_refused_log_records` | `receiver`, `transport` |
| Exported | `otelcol_exporter_sent_metric_points`, `otelcol_exporter_sent_log_records` | `exporter` |
| Export failed | `otelcol_exporter_send_failed_metric_points`, `otelcol_exporter_send_failed_log_records` | `exporter` |
| Dropped | `otelcol_exporter_enqueue_failed_metric_points`, `otelcol_exporter_enqueue_failed_log_records` (points an exporter could not queue because its queue was full; absent until the first one) | `exporter` |
| Queue size | `otelcol_exporter_queue_size` (vs `otelcol_exporter_queue_capacity`) | `exporter`, `data_type` |
| Export latency | `otelcol_exporter_http_duration_bucket` / `_sum` / `_count` (ms) | `net_peer_name` (`victoriametrics`, `victorialogs`), `http_status_code`, `le` |

`otelcol_exporter_http_duration` is the collector's `http_client_duration` (needs
`service.telemetry.metrics.level: detailed`), renamed by `metric_relabel_configs`; the same job
drops the collector's `http_server_*`, which would otherwise mix into the backend's
`http_server_duration` and `http_server_active`.

**VictoriaMetrics** (`job="victoriametrics"`)

| Panel | Series | Labels |
|---|---|---|
| Ingest rate | `vm_rows_inserted_total` (counter) | `type` (`promremotewrite` is the collector) |
| Rows rejected | `vm_rows_ignored_total` (`reason`), `vm_rows_invalid_total` (`type`) | |
| Active series | `vm_cache_entries{type="storage/hour_metric_ids"}` | `type` |
| Disk used | `vm_data_size_bytes` (sum over `type`) | `type` |
| Disk free | `vm_free_disk_space_bytes` | `path` |
| Retention | `flag{name="retentionPeriod"}` (value in the `value` label, e.g. `800d`) | `name`, `value`, `is_set` |
| Query latency | `vm_request_duration_seconds` (summary) | `path`, `quantile` |
| Requests | `vm_request_duration_seconds_count` (the summary's count: every request, errors included; there is no status label) | `path` |
| HTTP latency | `vm_http_request_duration_seconds_bucket` (VictoriaMetrics `vmrange` buckets, not `le`) | `path`, `vmrange` |
| HTTP errors | `vm_http_request_errors_total` | `path`, `protocol`, `reason` |

**Caddy** (`job="caddy"`, prod only; verified on a throwaway `caddy:2.10.2` with `per_host`)

| Panel | Series | Labels |
|---|---|---|
| Scrape health | `up{job="caddy"}` (written by the collector's scrape: 1 scraped, 0 down) | — |
| Requests | `caddy_http_requests_total` (no status label) | `server`, `handler`, `host` |
| Requests by status class, 401/403 | `caddy_http_request_duration_seconds_count` (group `code` by its first digit) | `server`, `handler`, `host`, `code`, `method` |
| Latency | `caddy_http_request_duration_seconds_bucket` (seconds) | as above + `le` |
| Upstream errors | `caddy_http_request_errors_total` (handler errors, e.g. an unreachable upstream) | `server`, `handler`, `host` |
| Upstream health | `caddy_reverse_proxy_upstreams_healthy` (stays 1 without active health checks) | `upstream` |
| In flight | `caddy_http_requests_in_flight` | `server`, `handler` |

**Mockup panels with no series:** MinIO per-bucket size
and object count exist only on `/minio/v2/metrics/bucket`, which is not scraped; the cluster totals
above stand in (one bucket, `wh-images`). VictoriaMetrics "retention" is a label value, not a
number.

## VictoriaLogs fields

Verified on `v1.53.0` with logs shipped by the backend through the collector
(`/insert/opentelemetry`): resource attributes are the stream fields, dots kept.

| Meaning | Field | Example |
|---|---|---|
| Service | `service.name` | `wh-backend` (stream field) |
| Level | `severity_text` (also `severity_number`) | `INFO`, `WARN` |
| Request id | `request_id` | `t15abc` (absent outside a request) |
| Message | `_msg` | |
| Time | `_time` | RFC 3339 with nanoseconds |

Also present: `scope.name` (logger), `scope.version`, `telemetry.sdk.*`. There is **no
`severity` field**: `"severity":i("warn")` matches nothing, `"severity_text":i("warn")` matches.
`"service.name":="wh-backend" "request_id":="t15abc" | sort by (_time) desc` returns the line, and
RFC 3339 `start`/`end` with fractional seconds are accepted. A bare phrase filter is
case-sensitive (`"malformed"` does not match `Malformed request body`); `i("malformed")` and
`i("MALFORMED REQUEST")` do. `VictoriaLogsRepository` uses exactly these: `severity_text` with
`i(...)` for the level (returned lower case), `i("...")` for the free text.

## Rollback

- **App images:** set `WH_IMAGE_TAG=<previous release>` (e.g. `v1.4.0`; every Stable Release tags
  backend, bridge and console with its version) in the server's `.env`, then `make deploy`. Remove
  it to go back to `latest`. While it is set, a manual Deploy run deploys that tag too. A release
  that predates the console has no `wh-console:<tag>`: stop the console (below) instead of pulling.
- **Compose and config changes** (collector pipelines, new services): put the previous release back
  in `/opt/wh/wh-backend` (`git reset --hard <tag>`) and `make deploy`. That leaves `victorialogs`
  running; stop it by hand if wanted.
- **Telemetry off, app untouched:** `TELEMETRY_ENABLED=false` (backend: no metrics, no logs),
  `OTEL_EXPORTER_OTLP_ENDPOINT=` (bridge), `RESTOCK_OTLP_URL=` (cron) in `.env`, then
  `up -d --no-deps backend bridge restock-cron`. The console keeps working on the data it has.
- Logs only: drop the `logs` pipeline and `otlphttp/victorialogs` from `collector-config.yml`, then
  `up -d --no-deps --force-recreate otel-collector`. The backend keeps running; its log exporter
  only warns on stdout.
- Scrapes: remove the `prometheus` receiver from the metrics pipeline (and in prod the second
  `--config`). OTLP metrics are unaffected.
- Console: `docker compose -f docker-compose.yml -f docker-compose.prod.yml stop console` and
  remove the Caddy `/console/*` route. The Caddy metrics options are independent and can stay.
- MinIO metrics: remove `MINIO_PROMETHEUS_AUTH_TYPE` from the `minio` service (back to token
  auth) and drop the `minio` scrape job, then `up -d --no-deps minio otel-collector`.
- `victorialogs` and its `victorialogs_data` volume can be removed once nothing points at them.

## Operations

- **Volumes and backup.** Prod named volumes are `wh-backend_<name>` (compose project
  `wh-backend`). `mongo_data` and `minio_data` hold business data: back them up as before.
  `victoriametrics_data` (800 days of metrics, the seeded history included) is worth a snapshot
  (`POST /snapshot/create` on `victoriametrics:8428` from `wh-network`, then copy
  `snapshots/<name>` out of the volume). `victorialogs_data` is 30 days of logs and can be lost.
  VictoriaMetrics, VictoriaLogs and the collector run as root, MinIO is forced to `0:0`; the
  backend and console write no volume.
- **Container logs.** The prod overlay rotates json-file logs at 10 MB x 3 for the console,
  VictoriaLogs, the collector and the cron; the older services keep the daemon's setting.
- **Resources.** VictoriaMetrics `-memory.allowedBytes=512MB`, VictoriaLogs `256MB`. No container
  memory limits, like the rest of the stack.
- **Exposure.** Everything the console added is internal: VictoriaLogs `9428` has no host port,
  prod clears the collector's and VictoriaMetrics' host ports, and the console has no host port
  (Caddy reaches it on `wh-proxy`).
- **Tokens in logs.** The console sends the JWT only in the `Authorization` header (the stream is
  a `POST`), so the console nginx's access log (`$request`: method, path and query) never holds a
  token, and the backend logs no request lines. The console opens no `?token=` WebSocket. If Caddy
  access logs are on, other clients' `/ws/v1/...?token=` URLs land there; filter the query
  (`format filter { request>uri query { delete token } }`).
- **CORS.** Not needed: the browser talks to one origin (Caddy, or the console nginx in dev) and
  the backend has no CORS configuration. The WebSocket handlers' `setAllowedOrigins("*")` predates
  the console and is not used by it.
- **Headers.** The console nginx sends a `Content-Security-Policy` (self only; frames limited to
  the dev-tools ports), `X-Frame-Options: DENY`, `nosniff` and a strict referrer policy. HSTS is
  Caddy's.

## Known limitations

- No `tomcat.connections.rejected`: Tomcat has no such attribute (see Metrics notes).
- Backend charts are up to `TELEMETRY_EXPORT_INTERVAL_MS` (10 s) old, plus the stream's 5 s
  evaluation. Scraped targets refresh every 30 s (Caddy 60 s); MinIO usage lags by minutes.
- A client whose network vanishes without closing holds a stream slot until TCP gives up (about
  15 minutes on Linux), its send buffer fills, or its token expires; a user has 5 slots.
- A stream ends when its JWT expires (`JWT_EXPIRATION_MS`, 24 h); the console then asks to sign in
  again.
- The restock "Next run" tile shows the default `RESTOCK_SCHEDULE` in UTC; an override in `.env`
  is not visible to the console.
- The Edge page is empty until the Caddy step is applied, and the collector logs one scrape
  warning a minute until then.
- Collector "dropped", MinIO per-bucket size and VictoriaMetrics retention as a number have no
  series (see Scraped series).

## Metrics notes

- **No `tomcat.connections.rejected`.** Tomcat (embedded, Spring Boot 4.0.6) exposes no
  rejected-connections attribute. Verified over JMX on a live app: `Tomcat:type=ThreadPool`
  has `currentThreadsBusy`, `currentThreadCount`, `maxThreads`, `connectionCount`,
  `maxConnections`, `acceptCount`, `keepAliveCount`; `Tomcat:type=GlobalRequestProcessor` has
  `requestCount`, `errorCount`, `processingTime`, `maxTime`, `bytesSent`, `bytesReceived`. The only
  `rejected*` attribute anywhere in the `Tomcat` domain is `Manager.rejectedSessions` (HTTP
  sessions, not connections). The Backend page shows `tomcat.connections` current vs max
  saturation instead.
- **Tomcat MBeans are off by default.** Spring Boot disables Tomcat's MBean registry, so
  `tomcat.threads` and `tomcat.connections` need `server.tomcat.mbeanregistry.enabled=true`
  (set as `SERVER_TOMCAT_MBEANREGISTRY_ENABLED` in both compose files). Without it those series
  are absent, not zero.
- **401s carry `route=UNMATCHED`.** Security rejects before handler mapping runs, so there is no
  route template yet. Spring's static-resource catch-all `/**` is also reported as `UNMATCHED`.
- **Latency and failure series for the Infrastructure boards** (OTLP, histograms in ms with the
  usual `_bucket`/`_sum`/`_count`):

  | Metric | Type | Dimensions | Recorded |
  |---|---|---|---|
  | `mongo.pool.checkout.failed` | counter | `reason` (`timeout`, `pool_closed`, `connection_error`, `unknown`) | backend, the driver's `connectionCheckOutFailed`; `timeout` is a pool wait timeout |
  | `kafka.publish.duration` | histogram | `topic` (`orders`, `order.completed`, else `other`), `result` (`ok`, `error`) | backend, from `send()` to the broker's acknowledgement (a `send()` that throws is only counted in `kafka.messages`) |
  | `bridge.publish.duration` | histogram | `flow` (`orders_out`), `result` (`ok`, `error`) | bridge, each RabbitMQ publish attempt including its confirm |
  | `bridge.processing.duration` | histogram | `flow` (`orders_out`, `orders_in`, `vehicles_in`), `result` (`ok`, `dropped`, `error`) | bridge, from receiving a message (Kafka record or AMQP delivery) to its outcome: commit, ack or requeue; includes retry waits |

  Rollout: the collector's MinIO relabel takes effect on the next deploy (the collector is always
  recreated); `minio.errors` before that is stored as `minio_s3_requests_errors_total` and is not
  migrated, so its chart starts empty.
- **No `kafka.messages{direction="in"}`.** The backend consumes no Kafka topic; it only
  publishes `orders` and `order.completed` (`direction="out"`).
