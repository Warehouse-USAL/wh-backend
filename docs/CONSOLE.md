# Backend console

SUPERADMIN-only console for the backend, served at `/console/`. This file records what the
platform side needs and what was verified against a running stack.

## Overview

| Service | Port | Notes |
|---|---|---|
| `console` | host `8083:80` (dev only) | nginx serving `console/`; in prod `ghcr.io/warehouse-usal/wh-console:latest` on `wh-network` + `wh-proxy`, reached through Caddy at `/console/` |
| `victorialogs` | `9428` internal | `victoriametrics/victoria-logs:v1.53.0`, named volume `victorialogs_data`, `-retentionPeriod=30d` |
| `otel-collector` | `4317`/`4318` (dev host only), `13133` health, `8888` own metrics (localhost) | metrics pipeline `[otlp, prometheus]` → VictoriaMetrics; logs pipeline `[otlp]` → VictoriaLogs. Prod adds `collector-config.prod.yml` (Caddy scrape) and joins `wh-proxy` |

Environment variables added for the console (default in brackets):

| Variable | Service | Default |
|---|---|---|
| `TELEMETRY_LOGS_ENDPOINT` | backend | `http://otel-collector:4318/v1/logs` |
| `CONSOLE_VICTORIALOGS_URL` | backend | `http://victorialogs:9428` |
| `CONSOLE_BRIDGE_URL` | backend | `http://bridge:8081` |
| `CONSOLE_MINIO_URL` | backend | `http://minio:9000` |
| `CONSOLE_VICTORIAMETRICS_URL` | backend | `http://victoriametrics:8428` |
| `CONSOLE_COLLECTOR_URL` | backend | `http://otel-collector:13133` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | bridge | `http://otel-collector:4318` (blank = no export) |
| `MINIO_PROMETHEUS_AUTH_TYPE` | minio | `public` (collector scrapes `/minio/v2/metrics/cluster` without a token; anyone reaching port 9000, host-published in prod too, can read these counts and sizes) |
| `RESTOCK_OTLP_URL` | restock-cron | `http://otel-collector:4318/v1/metrics` (blank = no push) |

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

## Rollout (prod)

`make deploy` (run by the Deploy workflow) does these steps on every deploy, in this order:

```sh
docker compose -f docker-compose.yml -f docker-compose.prod.yml pull backend bridge console
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps victorialogs
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps --force-recreate otel-collector
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps minio victoriametrics
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --no-deps backend bridge console restock-cron
```

- MinIO is recreated for `MINIO_PROMETHEUS_AUTH_TYPE`, so it restarts briefly (uploads fail for
  a few seconds).
- The collector config is a bind mount: when only `collector-config*.yml` changed, compose sees no
  change, hence `--force-recreate` (the collector restarts on every deploy).
- Order: the collector must have the logs pipeline before the new backend and bridge are deployed;
  otherwise their log export gets 404s and warns on stdout until it does.
- Then backend, bridge and console are deployed; apply the Caddy step below once.

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
| `metric` | as `POST /metrics/query`, but `range` (`5m`, `15m`, `1h`, `6h`, `24h`) instead of `from`/`to`. The server uses the console's window: `to = floor(now/step)*step`, `from = to - range + step` | the `/metrics/query` response; `mode: "replace"` |
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
frames end the stream with `limit`. A client that accepts nothing for `send-timeout` is dropped. A
disconnected client is noticed at the latest on the next heartbeat.

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

Observability: the stream is left out of `http.server.duration` and of the access log (it lasts as
long as its token). One INFO line on open and one on close (subscription count, duration, reason,
`request_id`; never the user). `DEBUG` on `com.usal.whbackend.service.console.stream` logs each
source start and stop with the number of active sources.

Proxies must not buffer the response:

- The console's nginx: `proxy_buffering off` for `/api/console/stream` (Task 23e; the backend's
  `X-Accel-Buffering: no` already asks nginx for it).
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
| Errors | `minio_s3_requests_errors_total`, `minio_s3_requests_4xx_errors_total` (`_5xx_` appears once one happens) | `api` |
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
| HTTP latency | `vm_http_request_duration_seconds_bucket` (VictoriaMetrics `vmrange` buckets, not `le`) | `path`, `vmrange` |
| HTTP errors | `vm_http_request_errors_total` | `path`, `protocol`, `reason` |

**Caddy** (`job="caddy"`, prod only; verified on a throwaway `caddy:2.10.2` with `per_host`)

| Panel | Series | Labels |
|---|---|---|
| Requests | `caddy_http_requests_total` (no status label) | `server`, `handler`, `host` |
| Requests by status class, 401/403 | `caddy_http_request_duration_seconds_count` (group `code` by its first digit) | `server`, `handler`, `host`, `code`, `method` |
| Latency | `caddy_http_request_duration_seconds_bucket` (seconds) | as above + `le` |
| Upstream errors | `caddy_http_request_errors_total` (handler errors, e.g. an unreachable upstream) | `server`, `handler`, `host` |
| Upstream health | `caddy_reverse_proxy_upstreams_healthy` (stays 1 without active health checks) | `upstream` |
| In flight | `caddy_http_requests_in_flight` | `server`, `handler` |

**Mockup panels with no series:** collector "dropped" (0.115 has no dropped counter; the batch
processor never drops and the exporters report failures as `send_failed`). MinIO per-bucket size
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

- Logs: drop the `logs` pipeline and `otlphttp/victorialogs` from `collector-config.yml`, then
  `docker compose up -d otel-collector`. The backend keeps running; its log exporter only warns
  on stdout.
- Scrapes: remove the `prometheus` receiver from the metrics pipeline (and in prod the second
  `--config`). OTLP metrics are unaffected.
- Console: `docker compose stop console` and remove the Caddy `/console/*` route. The Caddy
  metrics options are independent and can stay.
- MinIO metrics: remove `MINIO_PROMETHEUS_AUTH_TYPE` from the `minio` service (back to token
  auth) and drop the `minio` scrape job, then `up -d --no-deps minio otel-collector`.
- `victorialogs` and its `victorialogs_data` volume can be removed once nothing points at them.

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
- **No `kafka.messages{direction="in"}`.** The backend consumes no Kafka topic; it only
  publishes `orders` and `order.completed` (`direction="out"`).
