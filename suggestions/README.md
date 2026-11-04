# suggestions

Servicio en Go de recomendaciones sobre el historial de pedidos. Consume `order.completed` de Kafka
(Redpanda), guarda el modelo solo en Redis y expone una API HTTP protegida con el JWT del backend.

Documentación completa (arquitectura, API, modelo de Redis, filtrado colaborativo, decaimiento,
variables de entorno): [`docs/SUGGESTIONS.md`](../docs/SUGGESTIONS.md).

```
cmd/suggestions/      main: wiring, graceful shutdown, `healthcheck` subcommand
internal/config/      environment
internal/store/       Redis keys
internal/ingest/      order.completed -> Redis (MULTI/EXEC)
internal/consumer/    Kafka loop (fetch, apply, commit) and liveness
internal/decay/       hot:global decay (Lua EVAL)
internal/recommend/   cart, user (collaborative filtering) and hot + backtest
internal/auth/        JWT verification
internal/api/         Gin router and handlers
```

```
go vet ./... && go test -race ./...
```
