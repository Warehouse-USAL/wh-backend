# End-to-end suite (`make e2e-suite`)

A black-box suite that builds and starts the whole system locally in Docker, runs one test per capability against the real services, writes a JUnit report plus a results matrix, and tears everything down.

## How to run

```
make e2e-suite                      # build, start, wait for health, run all tests, tear down (~4 min after images are built)
PYTEST_ARGS="test_orders.py -q" make e2e-suite    # subset (pytest arguments)
KEEP=1 make e2e-suite               # leave the stack running afterwards
KEEP=1 e2e/suite.sh                 # first run, keep stack
PYTEST_ARGS="test_bridge.py -q" KEEP=1 e2e/suite.sh rerun   # re-run tests against the kept stack
```

Requirements: Docker with compose v2 (about 8 GB RAM). Nothing else; no Java, no Python on the host.

Outputs (git-ignored) in `e2e/results/`: `junit.xml`, `matrix.md` (human readable, per area and per test), `.env` (throwaway secrets generated for the run: JWT secret, Infra API key, admin password; never reuse real ones).

Safety: the stack uses compose project `whsuite`, publishes no host ports, uses the fake Infra (`fakeinfra`) and its own RabbitMQ container, and never touches any external host. `e2e/docker-compose.infra-real.yml` is not used.

## Architecture

- `e2e/suite.sh` - orchestrator: generates secrets, builds images (backend, bridge, fakeinfra, console, suggestions, runner), starts the stack with `SEED_DEMO=true` (the real warehouse: one zone `A`, one line, six positions `PA1`-`PA3`/`PM1`-`PM3` with QR `WH2|<name>`; tests that need more zones, lines or positions create their own through the factory), creates (but does not start) `fakeinfra`, runs the `runner` container, writes results and runs `down -v` on exit.
- `e2e/docker-compose.suite.yml` - overlay on the root compose: no host ports, isolated volumes, a local RabbitMQ (`suite/suite`), short bridge retry back-off, the `runner` service (mounts the Docker socket so resilience tests can stop/start containers).
- `e2e/suite/` - the pytest project: `conftest.py` (health wait, role fixtures, Kafka tap, RabbitMQ API client, results-matrix plugin, group ordering), `lib/` (HTTP client with readable failures, factory for products/zones/positions/orders, an `InfraClient` that plays Infra with the API key, websocket and SSE readers, Docker control).
- Test groups run in this order: default (Infra is played by the tests through `/infra/**`), `flow` (real chain: backend -> Kafka -> bridge -> RabbitMQ -> fakeinfra and back), `resilience` (stops/kills dependencies, always restores them).
- Known product bugs are kept as tests that assert the CORRECT behaviour, marked `@pytest.mark.known_failure(bug=..., reason=...)`. They are strict xfail: they show as "known-failure" now and turn into a hard failure (XPASS) the day the bug is fixed, forcing the marker to be removed.

## Results of the reference run

| Area | Total | Pass | Fail | Known failure | Skipped |
|---|---|---|---|---|---|
| auth | 56 | 54 | 0 | 2 | 0 |
| bridge | 8 | 8 | 0 | 0 | 0 |
| catalog | 30 | 29 | 0 | 1 | 0 |
| console | 23 | 23 | 0 | 0 | 0 |
| metrics_query | 30 | 30 | 0 | 0 | 0 |
| orders | 39 | 38 | 0 | 1 | 0 |
| platform | 15 | 14 | 0 | 1 | 0 |
| restock | 12 | 11 | 0 | 1 | 0 |
| stock_adjustments | 23 | 22 | 0 | 1 | 0 |
| suggestions | 11 | 10 | 0 | 1 | 0 |
| vehicles | 9 | 8 | 0 | 1 | 0 |
| warehouse | 17 | 16 | 0 | 1 | 0 |
| websockets | 16 | 16 | 0 | 0 | 0 |
| flow | 7 | 7 | 0 | 0 | 0 |
| resilience | 9 | 9 | 0 | 0 | 0 |
| **all** | 305 | 295 | 0 | 10 | 0 |

### Coverage matrix: capability -> test id -> status

Group `default` plays Infra by hand, `flow` uses the real chain, `resilience` stops dependencies.

| Test id | Capability | Group | Status | Note |
|---|---|---|---|---|
| `test_auth.py::test_login_every_seeded_role[SUPERADMIN]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[ADMIN_SYSTEM]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[ADMIN_WAREHOUSE]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[ADMIN_SALES]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[PROVIDER]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[DISPATCHER]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_every_seeded_role[OPERATOR]` | Login succeeds for every seeded role and returns a JWT plus the user's role | default | passed |  |
| `test_auth.py::test_login_response_never_leaks_password_hash` | Login body and user payloads contain no password / hash | default | passed |  |
| `test_auth.py::test_login_wrong_password_and_unknown_user_look_the_same` | Wrong password and unknown e-mail both give 401 INVALID_CREDENTIALS (no user enumeration) | default | passed |  |
| `test_auth.py::test_login_malformed_and_missing_fields` | Malformed JSON is 400 MALFORMED_REQUEST; missing fields never 500 | default | passed |  |
| `test_auth.py::test_login_has_no_lockout_valid_login_still_works_after_failures` | No account lockout exists: 8 bad attempts do not block the next valid login (documented behaviour) | default | passed |  |
| `test_auth.py::test_login_disabled_account_is_403` | A deactivated user cannot log in (403 ACCOUNT_DISABLED) and can again once reactivated | default | passed |  |
| `test_auth.py::test_deactivated_user_existing_token_is_rejected` | Deactivating a user invalidates the tokens it already holds | default | passed |  |
| `test_auth.py::test_no_token_is_401_with_json_error` | Protected endpoint without a token -> 401 UNAUTHORIZED JSON | default | passed |  |
| `test_auth.py::test_jwt_tampering_is_rejected` | Altered payload (role escalation) or stripped signature -> 401 | default | passed |  |
| `test_auth.py::test_jwt_signed_with_other_secret_is_rejected` | A well-formed token signed with a different secret -> 401 | default | passed |  |
| `test_auth.py::test_jwt_alg_none_is_rejected` | Unsigned (alg=none) token -> 401 | default | passed |  |
| `test_auth.py::test_jwt_expired_is_rejected_and_valid_forged_with_real_secret_is_accepted` | Expired token -> 401; same claims with a future exp (signed by the shared secret) -> 200 | default | passed |  |
| `test_auth.py::test_jwt_hmac_variants_accepted[HS256]` | The backend validates HS256/384/512 tokens made with the shared secret (the suggestions service relies on the same) | default | passed |  |
| `test_auth.py::test_jwt_hmac_variants_accepted[HS384]` | The backend validates HS256/384/512 tokens made with the shared secret (the suggestions service relies on the same) | default | passed |  |
| `test_auth.py::test_jwt_hmac_variants_accepted[HS512]` | The backend validates HS256/384/512 tokens made with the shared secret (the suggestions service relies on the same) | default | passed |  |
| `test_auth.py::test_garbage_authorization_header_does_not_break_public_endpoints` | A stale Authorization header must not 401 a public endpoint (file download, health, login) | default | passed |  |
| `test_auth.py::test_user_crud_lifecycle` | ADMIN_SYSTEM creates, reads, lists, updates and deactivates a user | default | passed |  |
| `test_auth.py::test_user_is_active_filter` | GET /users?isActive/active filter narrows to inactive users | default | passed |  |
| `test_auth.py::test_user_validation_and_conflicts` | Duplicate e-mail 409, bad e-mail/blank fields 400 VALIDATION_ERROR, unknown role 400, unknown id 404 | default | passed |  |
| `test_auth.py::test_unknown_role_has_stable_error_code` | Creating a user with an unknown role answers INVALID_ROLE without leaking class names | default | known-failure | BUG-USERS-1: POST /users with an unknown role answers BAD_REQUEST with the raw Java message 'No enum constant com.usal.whbackend.domain.UserRole.WIZARD' instead of INVALID_ROLE (UserService.createUser/updateUser call UserRole.valueOf unguarded; getUsers does map it) |
| `test_auth.py::test_reset_password_by_admin` | ADMIN_SYSTEM resets a user's password (204); the old one stops working, the new one works | default | passed |  |
| `test_auth.py::test_change_own_password` | Self-service change: wrong current -> 400 WRONG_CURRENT_PASSWORD, same -> 400 SAME_PASSWORD, ok -> new login works | default | passed |  |
| `test_auth.py::test_update_me_profile` | PATCH /users/me changes own name and address; role and e-mail stay put | default | passed |  |
| `test_auth.py::test_provider_supplier_link` | A PROVIDER account carries a supplier; PATCH sets it and a blank value clears it | default | passed |  |
| `test_auth.py::test_role_matrix[users list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[orders list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[products list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[vehicles list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[zones list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[positions list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[metrics catalog]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[query catalog]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[restock orders list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[receptions list]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create reception]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[console status]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[files listing]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create order]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create product]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[delete product]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create vehicle]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create zone]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[create restock order]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[apply restock suggestions]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[internal assign vehicle]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[internal patch vehicle]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[stock adjustment]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_role_matrix[product adjustments history]` | Role matrix: allowed roles pass authorization, every other role gets 403, anonymous gets 401 | default | passed |  |
| `test_auth.py::test_authorization_is_decided_before_validation` | An unauthorized role is told 403 even when its body is invalid (not 400) | default | known-failure | BUG-AUTHZ-1: @Valid/body parsing runs before @PreAuthorize on controller methods, so an unauthorized role gets 400 VALIDATION_ERROR/MALFORMED_REQUEST (and learns the schema) instead of 403 |
| `test_auth.py::test_error_shape_is_uniform_for_403` | 403 bodies are {"error": {"code": "ACCESS_DENIED", "message"}} | default | passed |  |
| `test_bridge.py::test_bridge_health_endpoint` | GET /healthz on the bridge answers 200 ok | default | passed |  |
| `test_bridge.py::test_order_reaches_rabbitmq_exactly_as_published_on_kafka` | Kafka `orders` -> amq.topic key `orders`: same JSON, persistent, application/json, pending then cancelled | default | passed |  |
| `test_bridge.py::test_each_order_is_delivered_once_and_in_order` | A burst of orders arrives in RabbitMQ complete, without duplicates, preserving creation order | default | passed |  |
| `test_bridge.py::test_infra_order_status_message_becomes_a_backend_callback` | A status message from Infra on the `vehicles` key (carrying order_id) drives POST /infra/orders/{id}/status: in_progress then completed | default | passed |  |
| `test_bridge.py::test_infra_telemetry_message_becomes_a_backend_callback` | Telemetry (vehicle_id, no order_id) drives POST /infra/vehicles/{id}/telemetry; last_qr is not forwarded and does not break it | default | passed |  |
| `test_bridge.py::test_rejected_messages_are_discarded_not_retried` | A 4xx answer (unknown order -> 404, finished order -> 409) discards the message: the queue drains, later messages still flow | default | passed |  |
| `test_bridge.py::test_garbage_messages_are_dropped_and_the_bridge_keeps_working` | Non-JSON and unclassifiable messages (neither order_id nor vehicle_id) are logged and dropped; the next telemetry still gets through | default | passed |  |
| `test_bridge.py::test_bridge_forwards_the_api_key_and_request_ids` | Callbacks carry X-Api-Key (otherwise they would be 401 and discarded) and the bridge logs correlate with backend logs by request_id | default | passed |  |
| `test_catalog.py::test_product_create_and_read_back` | Create a product with price/specs/images/dimensions and read it back (volume computed, stock zeroed) | default | passed |  |
| `test_catalog.py::test_product_sku_must_be_unique` | Duplicate SKU -> 409 SKU_ALREADY_EXISTS | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch0]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch1]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch2]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch3]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch4]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch5]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch6]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch7]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_validation[patch8]` | Negative dimensions/min/max or blank sku/name/category are rejected with 400 VALIDATION_ERROR | default | passed |  |
| `test_catalog.py::test_product_create_missing_dimensions_and_bad_category` | Missing dimensions -> VALIDATION_ERROR; unknown category -> INVALID_CATEGORY | default | passed |  |
| `test_catalog.py::test_product_zero_max_quantity_means_unlimited` | max_quantity_per_order = 0 imposes no per-order limit | default | passed |  |
| `test_catalog.py::test_product_categories` | GET /products/categories lists the accepted catalogue categories | default | passed |  |
| `test_catalog.py::test_product_list_filters_search_and_pagination` | List: category filter, search by name/SKU/description (literal, not regex), page size capped at 50, pagination block | default | passed |  |
| `test_catalog.py::test_product_patch_partial_update` | PATCH changes only the given fields; unknown id 404; negative values 400 | default | passed |  |
| `test_catalog.py::test_product_deactivate_hides_it` | PATCH is_active=false hides the product from GET (404) and the default list; is_active=false listing shows it | default | passed |  |
| `test_catalog.py::test_product_delete_unassigns_positions_and_hides` | DELETE /products/{id}: 204, product disappears, its positions are emptied and unassigned; sales cannot delete | default | passed |  |
| `test_catalog.py::test_product_location_lists_positions_and_total` | GET /products/{id}/location returns each active position holding it and the total stock | default | passed |  |
| `test_catalog.py::test_product_stock_numbers_physical_reserved_available` | stock.physical = units in active positions; pending orders raise reserved and lower available | default | passed |  |
| `test_catalog.py::test_upload_serve_and_delete_roundtrip` | Upload a PNG to MinIO, download it publicly with the right headers, list it (SUPERADMIN), delete it | default | passed |  |
| `jpeg-a.jpg]` | JPEG and WebP are accepted (sales may upload) | default | passed |  |
| `webp-a.webp]` | JPEG and WebP are accepted (sales may upload) | default | passed |  |
| `test_catalog.py::test_upload_rejections` | Empty file, unsupported type, content that doesn't match its declared type -> 400 | default | passed |  |
| `test_catalog.py::test_upload_size_limit_is_413` | A file over 5 MB is refused with 413 PAYLOAD_TOO_LARGE | default | passed |  |
| `test_catalog.py::test_upload_custom_path_and_traversal` | The path part is one safe segment: 'banners' lands under banners/, '../x' or a long name is a 400 | default | passed |  |
| `test_catalog.py::test_file_serving_unknown_and_traversal` | Unknown file 404; traversal attempts never succeed (400/404) | default | passed |  |
| `test_catalog.py::test_file_permissions` | Upload: ADMIN_SALES/WAREHOUSE/SUPERADMIN only; delete: WAREHOUSE/SUPERADMIN only; listing: SUPERADMIN only | default | passed |  |
| `test_catalog.py::test_file_write_without_token_is_401` | Anonymous upload/delete is 401 (unauthenticated), not 403 (authenticated but forbidden) | default | known-failure | BUG-FILES-1: /api/v1/files/** is permitAll in SecurityConfig (downloads are public), so an anonymous upload/delete reaches @PreAuthorize and gets 403 ACCESS_DENIED instead of 401 UNAUTHORIZED; only GET /api/v1/files was special-cased |
| `test_catalog.py::test_product_image_flow_and_cleanup_on_delete` | Upload an image, attach it to a product; replacing it or deleting the product removes the object from MinIO | default | passed |  |
| `status-None]` | Every /console/** endpoint: anonymous 401, any non-SUPERADMIN role 403 (JSON even for the stream), SUPERADMIN allowed | default | passed |  |
| `query-body1]` | Every /console/** endpoint: anonymous 401, any non-SUPERADMIN role 403 (JSON even for the stream), SUPERADMIN allowed | default | passed |  |
| `orders-None]` | Every /console/** endpoint: anonymous 401, any non-SUPERADMIN role 403 (JSON even for the stream), SUPERADMIN allowed | default | passed |  |
| `stream-body3]` | Every /console/** endpoint: anonymous 401, any non-SUPERADMIN role 403 (JSON even for the stream), SUPERADMIN allowed | default | passed |  |
| `test_console.py::test_console_status_reports_every_platform_service_up` | GET /console/status probes backend, bridge, mongodb, redpanda, minio, victoriametrics, victorialogs, otel-collector; all UP in the healthy stack | default | passed |  |
| `test_console.py::test_console_logs_find_a_request_by_its_id` | A request's X-Request-Id can be found back in the logs (backend -> OTEL -> VictoriaLogs -> console API) | default | passed |  |
| `test_console.py::test_console_logs_filters_and_limits` | Filter by service/level/text, newest first, limit default 100 and capped at 500; invalid service/level -> 400 | default | passed |  |
| `test_console.py::test_console_logs_bridge_lines_are_correlated` | The bridge ships its own logs with request ids, queryable with service=bridge | default | passed |  |
| `test_console.py::test_console_ws_events_feeds` | GET /console/ws-events/{feed} serves the recent websocket events of orders, user-orders, vehicles, stock-alerts; unknown feed 404 | default | passed |  |
| `test_console.py::test_console_stream_snapshot_then_live_updates` | The SSE stream sends a snapshot per subscription and then pushes live events: a new order shows up in the orders ws-events feed within seconds (the feed records what is pushed to connected websocket clients) | default | passed |  |
| `test_console.py::test_console_stream_metric_and_logs_subscriptions` | metric and logs subscriptions get a snapshot with the same payload shape as their REST twins | default | passed |  |
| `test_console.py::test_console_stream_rejects_invalid_subscriptions[subs0]` | Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened) | default | passed |  |
| `test_console.py::test_console_stream_rejects_invalid_subscriptions[subs1]` | Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened) | default | passed |  |
| `test_console.py::test_console_stream_rejects_invalid_subscriptions[subs2]` | Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened) | default | passed |  |
| `test_console.py::test_console_stream_rejects_invalid_subscriptions[subs3]` | Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened) | default | passed |  |
| `test_console.py::test_console_stream_rejects_invalid_subscriptions[subs4]` | Bad ids, unknown kinds, duplicate ids, unknown feeds or metrics and >200 subscriptions -> 400 (no stream opened) | default | passed |  |
| `test_console.py::test_console_stream_reports_a_failing_subscription_as_an_error_event` | A well-formed subscription that cannot be served (unknown metric) yields an `error` event with its id, while the others keep streaming | default | passed |  |
| `test_console.py::test_console_stream_ends_when_the_token_expires` | The stream sends `end` with reason token-expired when the JWT used to open it expires | default | passed |  |
| `test_console.py::test_console_stream_token_in_query_string_is_not_accepted` | The JWT travels in the Authorization header only: ?token= alone is 401 (keeps tokens out of URLs and logs) | default | passed |  |
| `test_console.py::test_console_ui_index_and_assets` | /console/ serves the SPA with security headers; every asset it references is served (200) with long-lived caching | default | passed |  |
| `test_console.py::test_console_ui_routing` | / redirects to /console/, /console to /console/, unknown SPA routes fall back to the index | default | passed |  |
| `test_console.py::test_console_ui_api_proxy_reaches_the_backend` | /api/* is proxied to the backend with the prefix stripped: health, login and an authenticated call work through nginx | default | passed |  |
| `test_console.py::test_console_ui_stream_through_proxy_is_unbuffered` | The SSE stream works through the nginx proxy: the first snapshot arrives promptly instead of being buffered | default | passed |  |
| `test_metrics_query.py::test_metrics_catalog_describes_metrics_and_computed_metrics` | GET /metrics/catalog lists metric descriptors (name, type, dimensions) and the computed restock_suggestions with its params; non-superadmins see only the business metrics | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch0-UNKNOWN_METRIC]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch1-UNKNOWN_DIMENSION]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch2-UNKNOWN_DIMENSION]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch3-UNSUPPORTED_AGGREGATION]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch4-QUERY_TOO_BROAD]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_rejections[patch5-QUERY_TOO_BROAD]` | Unknown metric/dimension, unsupported aggregation, too-fine step or too-wide range are 400 with the documented code | default | passed |  |
| `test_metrics_query.py::test_metrics_query_validation` | Missing required fields -> 400 VALIDATION_ERROR | default | passed |  |
| `test_metrics_query.py::test_metrics_query_returns_chart_ready_series_for_a_live_vehicle` | A vehicle reported through /infra shows up in wh.vehicle.battery as [epochSeconds, value] points (backend -> OTEL -> VictoriaMetrics -> API) | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_row_shape_and_stock_link` | A row per active product with demand/safety/reorder/target/inventory fields, available_stock matching the product's stock; filters narrow it | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_count_open_restock_orders` | on_order_stock = quantity of open restock orders minus what has been LOCATED on shelves | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_ignore_cancelled_restock_orders` | A cancelled restock order is not stock on order | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch0-alpha]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch1-alpha]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch2-recent_days]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch3-long_days]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch4-long_days]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch5-safety_days]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_suggestions_param_validation[patch6-lead_time_days]` | Out-of-range or missing params -> 400 INVALID_METRIC_PARAMS naming the offender | default | passed |  |
| `test_metrics_query.py::test_restock_apply_stores_the_recommendation_on_products` | apply writes product.restock for every active product (calculated_at set); filters are refused; DASHBOARD may simulate but not apply | default | passed |  |
| `test_metrics_query.py::test_query_catalog_is_role_scoped` | GET /query/catalog lists entities with fields/operators; users only for system admins | default | passed |  |
| `test_metrics_query.py::test_query_filters_sort_fields_and_pagination` | Entity query: filter operators, projection, sort, size ceiling (100), page metadata | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body0-UNKNOWN_FIELD]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body1-UNSUPPORTED_OPERATOR]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body2-INVALID_FILTER_VALUE]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body3-TOO_MANY_FILTERS]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body4-UNKNOWN_FIELD]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_rejections[body5-UNKNOWN_FIELD]` | Unknown field / unsupported operator / bad value / too many filters are 400 with their documented code | default | passed |  |
| `test_metrics_query.py::test_query_unknown_entity_and_role_limits` | Unknown entity 400; users entity: system admins only; DASHBOARD reads orders but entities it may not see are hidden | default | passed |  |
| `test_metrics_query.py::test_query_aggregation_needs_a_date_bound_and_groups` | Aggregations without a date filter are refused (UNBOUNDED_RANGE); with one they return a row per group | default | passed |  |
| `test_orders.py::test_create_order_happy_path` | Creating an order reserves stock, returns it pending with items, address, price total and default priority | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-DESTINATION_AREA_REQUIRED0]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-DESTINATION_AREA_REQUIRED1]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-ITEMS_REQUIRED0]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-ITEMS_REQUIRED1]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-MISSING_ADDRESS]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-MISSING_ADDRESS_STREET]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-MISSING_ADDRESS_POSTAL_CODE]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-INVALID_QUANTITY0]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-INVALID_QUANTITY1]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-PRODUCT_NOT_FOUND]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-QUANTITY_EXCEEDS_LIMIT]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_validation[<lambda>-DUPLICATE_PRODUCT_IN_ORDER]` | Each invalid order is refused with its own 400/404 code and reserves nothing | default | passed |  |
| `test_orders.py::test_create_order_insufficient_stock_and_inactive_product` | Asking for more than available -> INSUFFICIENT_STOCK; an inactive product -> PRODUCT_INACTIVE/NOT_FOUND | default | passed |  |
| `test_orders.py::test_create_order_malformed_json_and_roles` | Malformed body 400; DISPATCHER/PROVIDER/DASHBOARD/ADMIN_SYSTEM cannot create orders (403) | default | passed |  |
| `test_orders.py::test_order_priority_is_accepted` | An explicit priority is stored on the order | default | passed |  |
| `test_orders.py::test_idempotency_key_replays_the_same_order` | Same Idempotency-Key + same user: the retry returns the original order, reserves once and publishes once | default | passed |  |
| `test_orders.py::test_idempotency_key_of_another_user_conflicts` | The same key used by a different user -> 409 IDEMPOTENCY_KEY_USER_MISMATCH and no order | default | passed |  |
| `test_orders.py::test_idempotency_key_replay_returns_original_even_if_body_differs` | A replayed key with a different body gets the original order back (no second reservation) | default | passed |  |
| `test_orders.py::test_idempotency_key_concurrent_requests_create_one_order` | Racing requests with one key create exactly one order (others replay it or get IDEMPOTENCY_REQUEST_IN_PROGRESS) | default | passed |  |
| `test_orders.py::test_orders_topic_message_has_ordered_stops_from_qr_codes` | The pending message on topic `orders` is exactly {order_id,status,stops,destination,published_at}; stops are the positions' QR codes in zone/line/position order | default | passed |  |
| `test_orders.py::test_stops_are_deduplicated_and_ordered_by_line_then_position_name` | Two positions of one product in one zone: stops are in line-number then position-name order, one per position | default | passed |  |
| `test_orders.py::test_order_without_any_qr_still_created_with_empty_stops` | Positions without a QR are left out of the stops; an order with no stops at all is still created and published | default | passed |  |
| `test_orders.py::test_order_listing_filters_pagination_and_errors` | List: status/date/vehicle filters, newest-first pagination capped at 50, invalid filters -> 400 | default | passed |  |
| `test_orders.py::test_order_listing_default_order_is_newest_first` | The first page of GET /orders contains the order created a moment ago (newest first) | default | known-failure | BUG-ORDERS-3: GET /orders applies no sort (OrderRepository.findByFilters only pages), so page 0 is the oldest rows in insertion order: with the seeded history, a freshly created order is not on the first page and pagination order is not deterministic; the contract does not say but every consumer expects newest first |
| `test_orders.py::test_cancel_pending_order_restores_stock_and_tells_infra` | Cancelling a pending order: immediate, reason stored, reservation released, `cancelled` message on topic orders with the same stops | default | passed |  |
| `test_orders.py::test_cancel_rules_ownership_roles_and_states` | OPERATOR cancels only own orders (403 ORDER_NOT_OWNED), WAREHOUSE/SALES any; DISPATCHER/PROVIDER 403; repeat 409; unknown 404; body optional | default | passed |  |
| `test_orders.py::test_cancel_in_progress_order_waits_for_infra` | Cancelling an in-progress order only requests it (cancellation_requested_at); it stays in_progress until Infra reports cancelled, then the vehicle is freed | default | passed |  |
| `test_orders.py::test_infra_endpoints_require_the_api_key` | /infra/** answers 401 without X-Api-Key or with a wrong one, for both orders and telemetry; a JWT is not a substitute | default | passed |  |
| `test_orders.py::test_infra_status_validation` | Unknown order 404, bad/pending/missing status 400 INVALID_STATUS, in_progress without vehicle 400 VEHICLE_ID_REQUIRED | default | passed |  |
| `test_orders.py::test_infra_in_progress_assigns_vehicle_and_marks_it_busy` | in_progress with Infra's vehicle id creates/assigns the vehicle (UUIDv5 of the Infra id), starts the order and marks the vehicle busy | default | passed |  |
| `test_orders.py::test_infra_busy_vehicle_cannot_take_a_second_order` | A vehicle already working on an order -> 409 VEHICLE_ALREADY_BUSY for the next one | default | passed |  |
| `test_orders.py::test_completion_drains_stock_publishes_event_and_frees_vehicle` | completed: stock leaves the positions exactly once, reservation released, order.completed (camelCase) published, vehicle idle | default | passed |  |
| `test_orders.py::test_completion_drains_across_positions_fifo_without_going_negative` | An order bigger than one position drains several positions; no position goes below zero | default | passed |  |
| `test_orders.py::test_infra_cancel_and_late_status_on_finished_orders` | Infra may cancel a pending order; a different status on a finished order is 409 ORDER_NOT_MODIFIABLE, the same status a 200 no-op | default | passed |  |
| `test_orders.py::test_bad_timestamp_does_not_block_a_real_status_change` | An unparseable Infra timestamp falls back to now instead of failing the transition | default | passed |  |
| `test_orders.py::test_internal_order_admin_endpoints` | /internal/orders: assign-vehicle and status let WAREHOUSE force a transition; terminal orders refuse | default | passed |  |
| `test_orders.py::test_concurrent_orders_on_scarce_stock_never_oversell` | 12 parallel orders of 3 units on 10 units in stock: at most 3 win, the rest get INSUFFICIENT_STOCK, reserved never exceeds physical | default | passed |  |
| `test_orders.py::test_stock_numbers_across_the_whole_lifecycle` | available/reserved/physical through create -> in_progress -> completed, and through create -> cancel | default | passed |  |
| `test_platform.py::test_actuator_health_public_everything_else_protected` | /actuator/health is public and UP; info/env/metrics/prometheus are not exposed to anonymous callers | default | passed |  |
| `test_platform.py::test_openapi_documentation_is_served` | /v3/api-docs is valid OpenAPI 3 describing the API; Swagger UI is reachable | default | passed |  |
| `test_platform.py::test_every_documented_protected_operation_rejects_anonymous_calls` | Walk every GET path of the OpenAPI document: anonymous access is never 200 except for the public ones | default | passed |  |
| `test_platform.py::test_seed_demo_data_is_present` | SEED_DEMO: the 8 roles' demo accounts log in, catalogue/orders/vehicles are populated and the warehouse exists | default | passed |  |
| `test_platform.py::test_seeded_warehouse_is_one_zone_one_line_six_positions` | The seeded warehouse mirrors the real one: zone A, line 1, positions PA1-PA3 and PM1-PM3, active, one distinct product each | default | passed |  |
| `test_platform.py::test_seed_positions_have_the_qr_codes_infra_recognises` | Each seeded position's QR text is the configured prefix (WH2\|) plus its name: WH2\|PA1..PA3 and WH2\|PM1..PM3, all unique | default | passed |  |
| `test_platform.py::test_every_seeded_position_can_be_patched` | Re-sending a seeded position's own QR passes the capacity/volume guards (a past incident: a seeded position answered 409 to any edit) | default | passed |  |
| `test_platform.py::test_an_order_across_seeded_positions_publishes_their_qr_codes_as_stops` | An order for products held in PA3 and PM1 publishes stops [WH2\|PA3, WH2\|PM1] (position-name order) on topic `orders` | default | passed |  |
| `test_platform.py::test_error_contract_is_uniform` | Errors are always {"error":{"code","message"}} JSON: 404 on unknown route, 405 on wrong method, 401 anon, validation, malformed body | default | passed |  |
| `test_platform.py::test_unknown_route_uses_the_error_contract` | 404 for an unknown path uses the same {"error":{"code","message"}} envelope as every other error | default | known-failure | BUG-PLATFORM-1: unknown routes (404) and wrong methods (405) answer with Spring's default error body {timestamp,status,error,path} instead of the API's {error:{code,message}} contract (no NoHandlerFound/NoResourceFound mapping in the global exception handler) |
| `test_platform.py::test_responses_carry_request_id_and_security_headers` | Every response has X-Request-Id (echoed when supplied) and hardening headers | default | passed |  |
| `test_platform.py::test_cors_does_not_allow_arbitrary_origins` | A browser preflight from an unknown origin never gets an allow-origin header | default | passed |  |
| `test_platform.py::test_victoriametrics_receives_backend_metrics` | OTEL pipeline end to end: backend metrics (catalogued under /metrics/catalog) are queryable in VictoriaMetrics | default | passed |  |
| `test_platform.py::test_victorialogs_receives_logs` | OTEL pipeline end to end: backend logs are stored in VictoriaLogs | default | passed |  |
| `test_platform.py::test_file_storage_roundtrip_through_minio` | MinIO integration: see test_catalog file tests for upload/download/delete; here only that the platform exposes the documented upload route | default | passed |  |
| `test_restock.py::test_restock_order_create_get_and_list` | Create a restock order (pending, expected delivery date), read it with lead time and received-so-far, list with filters | default | passed |  |
| `test_restock.py::test_restock_order_validation` | Zero quantity / blank supplier / unknown product / expected date in the past are refused | default | passed |  |
| `test_restock.py::test_restock_order_cancel_rules` | Cancel: 200 soft delete with reason/author; hidden by default, visible with includeCancelled; cancel twice 409; with receptions 409 | default | passed |  |
| `test_restock.py::test_cancelled_restock_order_refuses_receptions` | A reception against a cancelled restock order -> 409 RESTOCK_ORDER_CANCELLED | default | passed |  |
| `test_restock.py::test_reception_with_full_assignment_adds_stock` | A reception whose assignments add up to the received quantity is COMPLETED and the stock lands in the positions | default | passed |  |
| `test_restock.py::test_reception_pending_location_then_assign_positions` | Without assignments the reception waits (PENDING_LOCATION); PATCH assigns positions step by step until COMPLETED, over-assigning is refused | default | passed |  |
| `test_restock.py::test_reception_assignment_errors` | Assignments above the received quantity, to an unknown/inactive/occupied position or over capacity are refused and change nothing | default | passed |  |
| `test_restock.py::test_reception_validation` | Missing supplier / unknown product / restock-order of another product / bad unit / zero quantity | default | passed |  |
| `test_restock.py::test_reception_unknown_delivery_unit_error_code` | POST /restock/receptions with an unknown delivery_unit explains it (INVALID_DELIVERY_UNIT) | default | known-failure | BUG-RECEPTION-1: an unknown delivery_unit in a JSON body answers MALFORMED_REQUEST ('not valid JSON') instead of INVALID_DELIVERY_UNIT: StockSize.fromValue throws IllegalArgumentException from the @JsonCreator, which Jackson wraps in ValueInstantiationException, while GlobalExceptionHandler.handleMalformedRequest only maps InvalidFormatException (the query-param path does return INVALID_DELIVERY_UNIT) |
| `test_restock.py::test_reception_listing_filters` | List receptions by product, restock order, status; newest first | default | passed |  |
| `test_restock.py::test_provider_sees_only_its_own_suppliers_restock_orders` | A PROVIDER's listing is forced to its supplier; another supplier's order is 404 (not 403); an unlinked provider is 403 PROVIDER_WITHOUT_SUPPLIER | default | passed |  |
| `test_restock.py::test_provider_delivers_against_its_order` | A PROVIDER registers a reception against its own restock order only (no assignments, supplier forced); positions are assigned later by the warehouse | default | passed |  |
| `test_stock_adjustments.py::test_spec_example_via_adjustment_endpoint` | 14 units, 6 break -> 8 left: in-process 5 respected, oldest pending (4) cancelled with INSUFFICIENT_STOCK, next (2) kept | default | passed |  |
| `test_stock_adjustments.py::test_spec_example_via_patch_current_stock` | The same reconciliation is triggered by PATCH current_stock 14 -> 8; the response carries cancelled_orders | default | passed |  |
| `test_stock_adjustments.py::test_patch_without_reduction_returns_empty_cancelled_orders` | PATCH always carries cancelled_orders (empty list when nothing was cancelled) | default | passed |  |
| `test_stock_adjustments.py::test_increase_is_recorded_and_cancels_nothing` | Raising stock by PATCH records MANUAL_INCREASE and never cancels orders | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_default_type_and_reason_optional` | type defaults to MANUAL_DECREASE; reason is optional (null) | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[0-400-VALIDATION_ERROR]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[-3-400-VALIDATION_ERROR]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[2.5-400-VALIDATION_ERROR]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[abc-400-MALFORMED_REQUEST]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[None-400-VALIDATION_ERROR]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_quantity_must_be_a_whole_number_within_stock[11-400-QUANTITY_EXCEEDS_POSITION_STOCK]` | quantity must be an integer > 0 and <= the position's stock; 2.5 is rejected, never truncated | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_type_and_position_rules` | MANUAL_INCREASE/other types are not accepted here; position without product 400; unknown position 404 | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_ignores_minimum_and_reserved_stock` | The adjustment can take stock below the product minimum and below what is reserved (the orders are then cancelled) | default | passed |  |
| `test_stock_adjustments.py::test_whole_multi_product_order_is_cancelled_and_releases_other_reservations` | A pending order that no longer fits is cancelled entirely, including its other products | default | passed |  |
| `test_stock_adjustments.py::test_orders_of_other_products_are_untouched` | Reconciliation looks only at the adjusted product's orders | default | passed |  |
| `test_stock_adjustments.py::test_inactive_position_adjustment_is_recorded_without_reconciling` | Lowering an inactive position is recorded, but its stock never counted so no order is cancelled | default | passed |  |
| `test_stock_adjustments.py::test_deactivating_or_deleting_a_position_reconciles` | is_active=false and DELETE on a stocked position record DEACTIVATED and cancel what no longer fits | default | passed |  |
| `test_stock_adjustments.py::test_unassigning_the_product_reconciles` | PATCH unassign_product with stock records UNASSIGNED and cancels the pending orders | default | passed |  |
| `test_stock_adjustments.py::test_delete_product_cancels_what_cannot_be_covered_but_spares_in_progress` | DELETE product: pending orders are cancelled, the in-progress one is respected; UNASSIGNED entry says 'Product deleted' | default | passed |  |
| `test_stock_adjustments.py::test_adjustment_history_endpoints_order_pagination_and_roles` | History: newest first, paginated; position history for WAREHOUSE/SUPERADMIN, product history also for SALES; 404s | default | passed |  |
| `test_stock_adjustments.py::test_concurrent_adjustments_of_different_positions_conflict_cleanly` | Simultaneous adjustments of one product's different positions: each answers 201 or 409 STOCK_CHANGE_CONFLICT, never 5xx, and the numbers add up | default | passed |  |
| `test_stock_adjustments.py::test_concurrent_adjustments_of_the_same_position_conflict_cleanly` | Parallel adjustments of one position never 500: they answer 201 or 409 STOCK_CHANGE_CONFLICT | default | known-failure | BUG-STOCK-1: parallel adjustments of the SAME position answer 500 (DataIntegrityViolationException: Mongo WriteConflict 112 TransientTransactionError on the position save) instead of 409 STOCK_CHANGE_CONFLICT; only the guard-document write inside OrderRepository.guardStockReconciliation maps that error, StockAdjustmentService.adjust saves the position first |
| `test_stock_adjustments.py::test_cancellation_is_published_only_for_committed_changes` | A rejected adjustment publishes no cancellation; a committed one publishes exactly one cancelled message per order | default | passed |  |
| `test_suggestions.py::test_healthz_needs_no_auth` | GET /healthz is public and healthy (Redis and consumer alive) | default | passed |  |
| `test_suggestions.py::test_recommendations_require_a_valid_jwt` | No token, malformed, wrong secret, expired and unsigned tokens -> 401 unauthorized in the service's own error format | default | passed |  |
| `test_suggestions.py::test_completed_orders_feed_the_redis_model` | A completed order reaches suggestions through Kafka `order.completed` and is recorded: user history, buyers and co-occurrence | default | passed |  |
| `test_suggestions.py::test_cancelled_orders_do_not_feed_the_model` | Only completed orders count: a cancelled order produces no recommendation data | default | passed |  |
| `test_suggestions.py::test_cart_recommendations_use_cooccurrence_and_get_equals_post` | Customers who bought A and B together: a cart with A gets B, never A itself; GET (?skus=) and POST ({skus}) agree; limit honoured | default | passed |  |
| `test_suggestions.py::test_cart_validation` | Empty cart, >100 SKUs, over-long SKU, bad limit and bad JSON -> 400 bad_request | default | passed |  |
| `test_suggestions.py::test_user_recommendations_collaborative_filtering_excludes_own_history` | Similar buyers' items are recommended; what the user already bought is not; a SUPERADMIN may read any user's, another OPERATOR may not | default | passed |  |
| `test_suggestions.py::test_cold_start_user_gets_the_hot_list` | A user without history receives the global popular list instead of nothing | default | passed |  |
| `test_suggestions.py::test_hot_ranks_by_popularity_and_honours_limit` | /hot returns SKUs by descending score; limit default 10, max 50 | default | passed |  |
| `test_suggestions.py::test_unknown_routes_return_json_not_found` | Unknown paths answer 404 | default | passed |  |
| `test_suggestions.py::test_replayed_event_is_ignored` | Kafka redelivery (at-least-once) must not double count an order: re-publishing the same order.completed leaves scores unchanged | default | known-failure | BUG-SUGGEST-1: Kafka redelivery of an order.completed event is counted twice (documented in suggestions/internal/ingest/apply.go: 'a replayed order counts twice'); no per-order dedupe key |
| `test_vehicles.py::test_register_list_and_get_vehicle` | POST /vehicles registers an OFFLINE vehicle; it is listed (paginated) and readable by id; WAREHOUSE can read but not register | default | passed |  |
| `test_vehicles.py::test_patch_vehicle_state` | PATCH /internal/vehicles/{id}: partial update of status/position/battery; unknown id 404; bad status 400; WAREHOUSE 403 | default | passed |  |
| `test_vehicles.py::test_battery_must_be_a_percentage` | A battery level outside 0..100 is refused or clamped, never stored as is | default | known-failure | BUG-VEHICLES-1: battery is a percentage but is stored unvalidated: PATCH /internal/vehicles/{id} and /infra telemetry accept 250 or -40 (no 0..100 check in VehicleService.updateVehicle / VehicleTelemetryService.apply) |
| `test_vehicles.py::test_first_telemetry_creates_the_vehicle_with_a_derived_id` | The first reading of an Infra id creates the vehicle under the deterministic UUIDv5; the response returns the backend id | default | passed |  |
| `test_vehicles.py::test_telemetry_status_transitions_and_partial_readings` | busy/idle/offline readings set the status; unknown status or missing battery keep the previous values instead of failing | default | passed |  |
| `test_vehicles.py::test_telemetry_fault_marks_the_vehicle_in_error` | A reading with an error and no status puts the vehicle in ERROR; a following healthy reading restores it and restarts its operation window | default | passed |  |
| `test_vehicles.py::test_telemetry_is_idempotent_for_the_same_reading` | Redelivered readings (the bridge is at-least-once) leave the same state | default | passed |  |
| `test_vehicles.py::test_busy_vehicle_released_when_its_order_is_cancelled_by_infra` | Vehicle/order coupling: assigned -> busy with current_order_id; Infra's cancelled -> idle again and free for a new order | default | passed |  |
| `test_vehicles.py::test_order_list_filter_by_assigned_vehicle` | GET /orders?vehicleId=... returns exactly the orders assigned to that vehicle | default | passed |  |
| `test_warehouse.py::test_zone_crud_and_soft_delete` | Zones are created inactive, activated/renamed with PATCH, listed, and DELETE is a soft delete | default | passed |  |
| `test_warehouse.py::test_zone_validation_and_conflicts` | Duplicate code 409, blank code / zero lines 400, unknown id 404, renaming onto an existing code 409 | default | passed |  |
| `test_warehouse.py::test_line_lifecycle_and_rules` | Lines: inactive zone refuses lines, duplicate number 409, listing, PATCH, soft delete, 404s | default | passed |  |
| `test_warehouse.py::test_zone_and_line_structural_limits_are_enforced` | A zone refuses lines beyond max_allowed_lines and a line refuses positions beyond max_allowed_positions | default | known-failure | BUG-WAREHOUSE-1: max_allowed_lines / max_allowed_positions are stored but never enforced: a zone with max_allowed_lines=1 accepts a second line (LineService.createLine / PositionService.createPosition never count siblings). Requirement is implied by the field names only - confirm with the owners |
| `test_warehouse.py::test_position_create_defaults_and_listing` | Positions are created inactive and empty; listed by line and in the flat listing; detail shows the assigned product | default | passed |  |
| `test_warehouse.py::test_position_in_inactive_line_and_validation` | Inactive line refuses positions (LINE_INACTIVE); blank name / capacity < 1 / bad size are 400 | default | passed |  |
| `test_warehouse.py::test_position_size_accepts_legacy_vocabulary[PEQUENO-CAJA]` | Legacy size names (PEQUENO/MEDIANO/GRANDE...) are accepted and stored under the canonical name | default | passed |  |
| `test_warehouse.py::test_position_size_accepts_legacy_vocabulary[mediano-MEDIO_PALLET]` | Legacy size names (PEQUENO/MEDIANO/GRANDE...) are accepted and stored under the canonical name | default | passed |  |
| `test_warehouse.py::test_position_size_accepts_legacy_vocabulary[GRANDE-PALLET]` | Legacy size names (PEQUENO/MEDIANO/GRANDE...) are accepted and stored under the canonical name | default | passed |  |
| `test_warehouse.py::test_position_size_accepts_legacy_vocabulary[small-CAJA]` | Legacy size names (PEQUENO/MEDIANO/GRANDE...) are accepted and stored under the canonical name | default | passed |  |
| `test_warehouse.py::test_qr_code_uniqueness_on_create_and_patch` | A QR text belongs to one position: duplicate on POST or PATCH -> 409 QR_CODE_ALREADY_EXISTS; re-sending your own is fine | default | passed |  |
| `test_warehouse.py::test_qr_code_wire_format_snake_case_only` | The wire field is qr_code; a camelCase qrCode is ignored; a blank qr_code clears it | default | passed |  |
| `test_warehouse.py::test_stock_guards_capacity_volume_and_negative` | current_stock above maximum_capacity or above the container volume -> 400 STOCK_EXCEEDS_CAPACITY; negative -> 400 | default | passed |  |
| `test_warehouse.py::test_one_product_per_position_and_unassign` | A position holds one product: assigning another -> 409 POSITION_ALREADY_OCCUPIED until unassign_product clears it | default | passed |  |
| `test_warehouse.py::test_position_deactivate_and_delete_are_soft_and_stop_counting` | Deactivating or DELETEing a position keeps the row but its stock stops counting for the product | default | passed |  |
| `test_warehouse.py::test_validate_fit` | POST /warehouse/positions/validate-fit computes volumes and the maximum units that fit | default | passed |  |
| `test_warehouse.py::test_available_positions_for_reception` | GET /warehouse/positions/available lists active, right-sized positions that are empty or hold this product, roomiest first | default | passed |  |
| `orders]` | Each feed accepts its documented roles (101) and refuses the others (403) | default | passed |  |
| `vehicles]` | Each feed accepts its documented roles (101) and refuses the others (403) | default | passed |  |
| `alerts]` | Each feed accepts its documented roles (101) and refuses the others (403) | default | passed |  |
| `orders]` | No token, garbage token, token signed with another secret and an expired token are all refused (403) | default | passed |  |
| `vehicles]` | No token, garbage token, token signed with another secret and an expired token are all refused (403) | default | passed |  |
| `alerts]` | No token, garbage token, token signed with another secret and an expired token are all refused (403) | default | passed |  |
| `someone]` | No token, garbage token, token signed with another secret and an expired token are all refused (403) | default | passed |  |
| `test_websockets.py::test_ws_user_feed_only_for_the_owner` | /ws/v1/orders/{userId} accepts a token whose subject is that user and refuses anyone else, admins included | default | passed |  |
| `test_websockets.py::test_order_events_reach_the_orders_feed` | Creating and cancelling an order pushes order.updated events (full order payload) to /ws/v1/orders | default | passed |  |
| `test_websockets.py::test_orders_feed_fans_out_to_every_client_and_survives_disconnects` | Two simultaneous clients both get the event; one leaving does not stop delivery to the other | default | passed |  |
| `test_websockets.py::test_user_feed_delivers_only_the_users_own_orders` | /ws/v1/orders/{userId} receives the user's own order events and none of other users' orders | default | passed |  |
| `test_websockets.py::test_stock_reconciliation_cancellations_are_pushed` | Orders cancelled by a stock adjustment are announced on the orders feed (after the commit) | default | passed |  |
| `test_websockets.py::test_vehicle_events_on_telemetry_and_faults` | Telemetry pushes vehicle.updated; a reading carrying an error also pushes vehicle.error with the fault | default | passed |  |
| `test_websockets.py::test_stock_alert_when_an_order_leaves_the_product_below_its_minimum` | Completing an order that leaves physical stock under the product minimum pushes stock.alert on /ws/v1/stock/alerts | default | passed |  |
| `test_websockets.py::test_no_stock_alert_while_stock_stays_above_the_minimum` | No alert when the remaining stock is still at or above the minimum | default | passed |  |
| `test_websockets.py::test_ws_ignores_client_messages` | Feeds are server-push only: frames sent by the client are ignored and the socket stays open | default | passed |  |
| `test_flow.py::test_full_order_lifecycle_through_the_real_chain` | pending -> in_progress -> completed driven only by fakeinfra: vehicle assigned, timestamps set, stock physically drained, order.completed published | flow | passed |  |
| `test_flow.py::test_the_fake_vehicle_is_registered_and_returns_to_idle` | Telemetry from fakeinfra creates its vehicle; during the order it is busy with the order, afterwards idle and free | flow | passed |  |
| `test_flow.py::test_orders_are_processed_by_a_single_vehicle_one_after_another` | Several orders created together all complete, each with the fake vehicle, none stuck | flow | passed |  |
| `test_flow.py::test_multi_product_order_completes_and_drains_every_product` | An order with two products in different positions completes and drains both | flow | passed |  |
| `test_flow.py::test_order_spanning_two_positions_of_one_product` | Stock split over two positions: one order for more than a single position holds completes and drains the total | flow | passed |  |
| `test_flow.py::test_order_cancelled_right_after_creation_stays_cancelled` | A cancel racing the pickup: the order ends cancelled, stock is released and untouched, the fake vehicle is not left busy | flow | passed |  |
| `test_flow.py::test_completed_order_cannot_be_cancelled_and_user_sees_it_in_history` | After completion the cancel is refused and the order is in the user's list as completed | flow | passed |  |
| `test_resilience.py::test_kafka_outage_orders_are_not_lost` | Redpanda down: the API keeps accepting orders; once Kafka is back, every order created during the outage reaches Kafka and RabbitMQ | resilience | passed |  |
| `test_resilience.py::test_rabbitmq_outage_bridge_reconnects_and_nothing_is_lost` | RabbitMQ down: orders are still accepted; after it returns the bridge reconnects, delivers the backlog and Infra callbacks flow again | resilience | passed |  |
| `test_resilience.py::test_bridge_restart_resumes_from_the_committed_offset` | Bridge down while orders are created: no loss and no duplicates once it is started again | resilience | passed |  |
| `test_resilience.py::test_mongodb_outage_fails_fast_and_cleanly` | MongoDB down: requests answer 503 {error} within 10s and health reports DOWN quickly | resilience | passed |  |
| `test_resilience.py::test_mongodb_outage_recovers_without_restarting_the_backend` | After MongoDB returns the backend serves again by itself, with all earlier data intact | resilience | passed |  |
| `test_resilience.py::test_minio_outage_only_affects_files` | MinIO down: catalogue, orders and health are unaffected; file upload fails with an error instead of hanging; uploads work again afterwards | resilience | passed |  |
| `test_resilience.py::test_suggestions_redis_outage_does_not_touch_the_backend_and_the_consumer_catches_up` | Suggestions' Redis down: backend unaffected, the service reports unhealthy, and the order completed meanwhile is applied after Redis returns (offset not committed before applying) | resilience | passed |  |
| `test_resilience.py::test_backend_crash_mid_order_recovers_and_the_order_can_finish` | kill -9 the backend with an order in progress: it restarts by itself state intact (no seed duplication, same JWTs valid) and Infra can still complete the order | resilience | passed |  |
| `test_resilience.py::test_graceful_backend_restart_keeps_websocket_clients_able_to_reconnect` | After a backend restart a new websocket client gets events again (sessions are per process, clients must reconnect) | resilience | passed |  |

## Product bugs found (tests keep asserting the correct behaviour)

| Id | Severity | Area | Summary | Suspected location | Status |
|---|---|---|---|---|---|
| BUG-ORDERS-1 | HIGH | orders | Overselling: 12 parallel orders of 3 units on 10 in stock all return 201 | `OrderService.createOrder` is `@Transactional` but the per-product lock is released at the end of `executeCreateOrder`, before commit | fixed (this branch) |
| BUG-ORDERS-2 | MEDIUM | orders | Concurrent requests with the same `Idempotency-Key` give 500 (Mongo WriteConflict) instead of replay/409; no duplicate order | idempotency claim in `OrderService` / idempotency repository | fixed (this branch) |
| BUG-STOCK-1 | MEDIUM | stock | Parallel adjustments of the same position give 500 instead of 409 `STOCK_CHANGE_CONFLICT` | `StockAdjustmentService.adjust` (only `guardStockReconciliation` maps the conflict) | owned by PR #150 |
| BUG-AUTH-1 | MEDIUM | auth | JWT of a deactivated user keeps working until it expires | `JwtAuthFilter` never consults the user store | fixed (this branch) |
| BUG-RESTOCK-1 | MEDIUM | metrics | Cancelled restock orders still count as stock on order in `/metrics/restock-suggestions` | `RestockSuggestionService.findOnOrder` ignores status | fixed (this branch) |
| BUG-RESIL-1 | MEDIUM | resilience | MongoDB down: requests hang about 30 s then 500 with Spring default body; `/actuator/health` hangs instead of reporting DOWN | Mongo client `serverSelectionTimeout`, missing 503 mapping, health indicator | fixed (this branch) |
| BUG-ORDERS-3 | LOW/MEDIUM | orders | `GET /orders` has no sort; page 0 is the oldest rows | `OrderRepository.findByFilters` | deferred (contract / owner input) |
| BUG-RECEPTION-1 | LOW | restock | Unknown `delivery_unit` in JSON body gives MALFORMED_REQUEST instead of INVALID_DELIVERY_UNIT | `StockSize.fromValue` + `GlobalExceptionHandler.handleMalformedRequest` | deferred (contract / owner input) |
| BUG-USERS-1 | LOW | users | Unknown role on `POST /users` leaks Java enum message instead of INVALID_ROLE | `UserService.createUser/updateUser` | deferred (contract / owner input) |
| BUG-AUTHZ-1 | LOW | auth | Body validation runs before `@PreAuthorize`: unauthorized roles get 400 instead of 403 | controller method security ordering | deferred (contract / owner input) |
| BUG-FILES-1 | LOW | files | Anonymous file upload/delete answers 403 instead of 401 | `SecurityConfig` (`/api/v1/files/**` permitAll) | deferred (contract / owner input) |
| BUG-PLATFORM-1 | LOW | platform | Unknown route (404) and wrong method (405) use Spring's default body, not `{error:{code,message}}` | `GlobalExceptionHandler` lacks NoResourceFound / HttpRequestMethodNotSupported mapping | deferred (contract / owner input) |
| BUG-VEHICLES-1 | LOW | vehicles | Battery outside 0..100 accepted via PATCH and telemetry | `VehicleService.updateVehicle`, `VehicleTelemetryService.apply` | deferred (contract / owner input) |
| BUG-WAREHOUSE-1 | LOW | warehouse | `max_allowed_lines` / `max_allowed_positions` are never enforced (requirement implied by names only) | `LineService.createLine`, `PositionService.createPosition` | deferred (contract / owner input) |
| BUG-SUGGEST-1 | LOW | suggestions | A redelivered `order.completed` event is counted twice (documented limitation) | `suggestions/internal/ingest/apply.go` | owned by PR #148 |

Observations that are not asserted as bugs: no login lockout (a passing test documents it); deleting a missing file answers 204 (idempotent); a pending order can move straight to completed via `/infra`; a metrics query with `from > to` answers QUERY_TOO_BROAD; the first file upload right after a MinIO restart was observed once to stall (>30 s) in an exploratory run, not reproduced in the full run.

## Not covered

- Real Infra and real RabbitMQ (out of scope by design; `fakeinfra` plays Infra). Orders with an empty stop list (fakeinfra cancels them) were not exercised because the API offers no way to create one.
- Browser rendering of the console UI: only the static delivery, security headers, SPA fallback, `/api` proxy and the SSE stream through the proxy are checked, not React behaviour.
- Load/performance, TLS, and the restock-cron schedule (the container is started, and the restock suggestion/apply API is tested, but the cron timing is not).
