# bridge

Servicio en Go que traduce entre el mundo de Infra (RabbitMQ) y el del backend
transaccional (`../backend`). No guarda estado ni conoce el depósito: solo
convierte formatos y reintenta. Es temporal: si Infra adopta el contrato del
backend, se borra esta carpeta y el backend no cambia.

Contrato del backend: https://github.com/Warehouse-USAL/wh-backend/issues/128

## Flujos

```
backend ──Kafka `orders`──► bridge ──amq.topic, key `orders`──► Infra
backend ◄──HTTP── bridge ◄──cola `wh-backend.vehicles` (key `vehicles`)── Infra
```

**Órdenes (backend → Infra).** Lee el topic `orders` con el group `wh-bridge` y
republica el mensaje tal cual en `amq.topic` con la routing key `orders`
(persistente, `application/json`, con publisher confirms). El offset de Kafka
se confirma recién cuando RabbitMQ confirmó el mensaje. La publicación es
`mandatory`: si no hay ninguna cola bindeada a `orders` (por ejemplo Infra
caída sin suscripción durable), reintenta en vez de perder la orden. Si falla,
reintenta con backoff hasta lograrlo.

**Estados y telemetría (Infra → backend).** Consume la cola durable
`wh-backend.vehicles`, bindeada a `amq.topic` con la routing key exacta
`vehicles` (nunca un comodín), de a un mensaje por vez. Según el contenido:

| Mensaje de Infra | Llamada al backend |
|---|---|
| Trae `order_id` (`OrderStatus`) | `POST /infra/orders/{order_id}/status` con `status`, `vehicle_id`, `timestamp` |
| Trae `vehicle_id` sin `order_id` (`VehicleTelemetry`) | `POST /infra/vehicles/{vehicle_id}/telemetry` con el mensaje sin `last_qr` |
| Ninguno de los dos | Se loguea y se descarta |

Todas las llamadas llevan `X-Api-Key` y `Content-Type: application/json`.

| Respuesta del backend | Qué hace el bridge |
|---|---|
| 2xx | confirma (ack) el mensaje |
| 4xx (404, 409, 400...) | loguea el payload y lo descarta: nunca reintenta |
| 408, 429, 5xx, error de red, timeout | no confirma y reintenta con backoff (`RETRY_MIN`..`RETRY_MAX`) |

Si el proceso se detiene con un mensaje en vuelo, se devuelve a la cola. Como la
entrega es al menos una vez, el backend tiene que tolerar mensajes repetidos.

## Variables de entorno

| Variable | Obligatoria | Default | Descripción |
|---|---|---|---|
| `KAFKA_BROKERS` | sí | | Brokers separados por coma |
| `AMQP_URL` | sí | | `amqp://usuario:clave@host:5672/` |
| `BACKEND_URL` | sí | | Base del backend, p. ej. `http://backend:8080` |
| `INFRA_API_KEY` | sí | | Se envía como `X-Api-Key` |
| `KAFKA_ORDERS_TOPIC` | no | `orders` | |
| `KAFKA_GROUP_ID` | no | `wh-bridge` | |
| `AMQP_QUEUE` | no | `wh-backend.vehicles` | |
| `HTTP_TIMEOUT` | no | `5s` | Timeout por llamada al backend |
| `RETRY_MIN` / `RETRY_MAX` | no | `1s` / `30s` | Backoff exponencial |
| `HEALTH_ADDR` | no | `:8081` | `GET /healthz` |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | no | | Base OTLP/HTTP del collector, p. ej. `http://otel-collector:4318`; vacío = sin exportar métricas ni logs |

Si falta alguna obligatoria, el servicio no arranca y nombra todas las que
faltan. La clave de la API y la contraseña de AMQP nunca se loguean.

## Probar

```sh
go vet ./...
go test -race ./...
```

Los tests usan fakes y `httptest`; no necesitan Kafka ni RabbitMQ.

## Docker

```sh
docker build -t wh-bridge .                         # el bridge
docker build --target fakeinfra -t wh-fakeinfra .   # Infra simulada
```

`cmd/fakeinfra` es un Infra de mentira que cumple el contrato (`AMQP_URL`,
`FAKE_VEHICLE_ID` = `VHC-001`, `FAKE_STEP_DELAY` = `1s`): toma cada orden
`pending`, publica `in_progress`, telemetría `busy` con `last_qr` en cada stop,
`completed` y telemetría `idle`; a una orden `cancelled` le responde
`cancelled` y libera al vehículo (el backend ya canceló la orden en el momento;
ese aviso no cambia su estado). Sirve para pruebas E2E sin el rover
real.

El healthcheck de la imagen es `/bridge healthcheck` (la imagen no tiene shell).
