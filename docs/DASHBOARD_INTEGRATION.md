# Guía de integración — Dashboard (Grupo 3)

Todo lo que necesitan para construir el dashboard contra el backend. Cubre las 21 métricas que
pidieron, con el cuerpo exacto de cada request.

**No hace falta instalar ningún cliente de métricas.** El backend envuelve OpenTelemetry y
VictoriaMetrics: ustedes hablan HTTP + JSON contra dos endpoints y listo.

---

## 1. En dos minutos

Hay **dos APIs**, y la diferencia es qué tipo de pregunta responde cada una:

| API | Responde | Fuente |
|---|---|---|
| `POST /metrics/query` | "¿cómo evolucionó esto en el tiempo?" — series temporales de los rovers | VictoriaMetrics |
| `POST /query/{entidad}` | "¿cuánto/cuántos hay, agrupado por …?" — datos de negocio | MongoDB |

Cada una tiene un catálogo que se describe a sí mismo. **Empiecen por ahí**: el catálogo dice qué
campos existen, qué operadores acepta cada uno y qué agregaciones son válidas. Si algo no está en
el catálogo, no se puede consultar — no es un permiso que falte, simplemente no existe.

### La división de responsabilidades

El backend **devuelve datos crudos**. No calcula MTBF, ni % de cumplimiento, ni "SKU en riesgo".

Eso es deliberado, no una limitación: esas definiciones son suyas. Si el backend decidiera que
"en riesgo" son 7 días de cobertura, ustedes quedarían atados a esa decisión. En cambio les
entregamos fallas, tiempos, demanda y stock, y ustedes dividen y aplican sus umbrales.

Cada métrica de esta guía dice explícitamente qué devuelve el backend y qué calculan ustedes.

---

## 2. Autenticación

```http
POST /auth/login
Content-Type: application/json

{"email": "dashboard@smartwarehouse.local", "password": "Demo1234!"}
```

```json
{
  "token": "eyJhbGciOiJIUzI1Ni...",
  "user": {
    "id": "u-dashboard",
    "name": "Panel de Monitoreo",
    "email": "dashboard@smartwarehouse.local",
    "role": "DASHBOARD"
  }
}
```

Manden el token en **todas** las llamadas:

```http
Authorization: Bearer <token>
```

El rol `DASHBOARD` es de **solo lectura por construcción**: no aparece en ningún endpoint de
escritura. No pueden romper nada desde el dashboard aunque se equivoquen.

> ⚠️ **El token dura 24 horas y no hay refresh todavía.** Para una demo alcanza. Si el dashboard
> va a quedar corriendo, manejen el 401 volviendo a hacer login. Avísennos si necesitan algo de
> vida más larga.

---

## 3. Descubrimiento

### `GET /metrics/catalog`

```json
{
  "name": "wh.vehicle.state",
  "display_name": "Vehicle state",
  "unit": "1",
  "type": "gauge",
  "dimensions": ["vehicle_id", "state"],
  "permitted_aggregations": ["count", "avg", "max", "last"]
}
```

- `dimensions` — las únicas etiquetas que pueden usar en `filters` y `group_by`
- `permitted_aggregations` — las únicas válidas **para esa métrica**

### `GET /query/catalog`

Devuelve `["orders", "products", "vehicles", "positions"]` con sus campos:

```json
{
  "name": "status",
  "type": "enum",
  "filterable": true,
  "sortable": true,
  "selectable": true,
  "operators": ["eq", "exists", "in", "ne", "nin"]
}
```

`orders` expone 14 campos. Consulten el catálogo en vez de copiar una lista de esta guía: el
catálogo es la fuente de verdad y no se desactualiza.

---

## 4. API de métricas (rovers)

```http
POST /metrics/query
{
  "metric": "wh.vehicle.state",
  "from": "2026-08-21T00:00:00Z",
  "to":   "2026-08-23T00:00:00Z",
  "step": "6h",
  "agg":  "count",
  "filters":  {"state": "BUSY"},
  "group_by": ["vehicle_id"]
}
```

```json
{
  "metric": "wh.vehicle.state",
  "unit": "1",
  "step": "6h",
  "series": [
    {"labels": {}, "points": [[1787351848, 3.5555555555555554], [1787373448, 0.2222222222222222]]}
  ]
}
```

`points` son pares `[epochSegundos, valor]` — listos para cualquier librería de gráficos.

### Las tres métricas publicadas

| Métrica | Tipo | Etiquetas | Agregaciones |
|---|---|---|---|
| `wh.vehicle.battery` | gauge | `vehicle_id` | `avg` `min` `max` `last` |
| `wh.vehicle.state` | gauge 1/0 | `vehicle_id`, `state` | `count` `avg` `max` `last` |
| `wh.vehicle.transitions` | counter | `vehicle_id`, `from`, `to`, `category` | `increase` `rate` |

`state` es `IDLE` / `BUSY` / `OFFLINE` / `ERROR`. La métrica vale 1 si el rover está en ese estado
y 0 si no, para **todos** los estados — así sumar da un conteo.

> **Por qué las agregaciones dependen del tipo.** Un counter sólo sube; pedirle un promedio da un
> número sin sentido. El backend rechaza la combinación con `UNSUPPORTED_AGGREGATION` en vez de
> devolver algo que parezca razonable y no lo sea.

### Dos cosas que sorprenden

**1. `count` da decimales.** En el ejemplo de arriba aparece `3.5555…`. No es un error: con
`step=6h`, cada punto es el **promedio de rovers en ese estado durante esas 6 horas**. Un rover que
estuvo ocupado la mitad del bloque aporta 0.5.

Si quieren un número entero tipo "ahora mismo", usen un `step` chico (`1m`, `5m`). Si quieren
"actividad de la flota en 24h", un `step` grande es exactamente lo que buscan.

**2. Retención: 30 días.** VictoriaMetrics guarda 30 días y `from`/`to` no pueden abarcar más de
31. Para el histórico de un semestre habría que subir la retención — díganos si lo necesitan.

---

## 5. API de consultas (negocio)

Un solo endpoint, dos modos.

### Modo documento — devuelve filas

```json
POST /query/orders
{
  "filters": [{"field": "status", "op": "eq", "value": "COMPLETED"}],
  "sort":    [{"field": "created_at", "dir": "desc"}],
  "fields":  ["id", "status", "created_at"],
  "page": 0, "size": 25
}
```

### Modo agregado — devuelve grupos

Agreguen `group_by` y/o `aggregates` y el endpoint agrupa:

```json
POST /query/orders
{
  "filters": [
    {"field": "created_at", "op": "gte", "value": "2026-06-24T00:00:00Z"},
    {"field": "created_at", "op": "lt",  "value": "2026-08-24T00:00:00Z"}
  ],
  "unwind": "items",
  "group_by":   [{"field": "items.sku", "as": "sku"}],
  "aggregates": [{"op": "sum", "field": "items.quantity", "as": "units"}],
  "sort": [{"field": "units", "dir": "desc"}],
  "size": 3
}
```

```json
{
  "pagination": {"page": 0, "size": 3, "total_elements": 3, "total_pages": 1},
  "items": [
    {"units": 12, "sku": "OTR-001"},
    {"units": 12, "sku": "TEC-002"},
    {"units": 12, "sku": "HER-001"}
  ]
}
```

La respuesta tiene **la misma forma** en los dos modos: `items` + `pagination`.

### Reglas

- **Operadores**: `eq` `ne` `gt` `gte` `lt` `lte` `in` `nin` `contains` `exists`
- **Agregados**: `count` (sin `field`), `sum`, `avg`, `min`, `max`
- **Buckets de fecha**: `hour`, `day`, `month` — sólo sobre campos de fecha
- **`as`** nombra la columna de salida. Minúsculas, dígitos y guión bajo.
- **`unwind`** es obligatorio para usar cualquier campo `items.*`
- **`sort`** en modo agregado usa los **alias**, no los campos originales
- **`timezone`** opcional, por defecto `America/Argentina/Buenos_Aires`

### Campos derivados de `orders`

Calculados por el backend porque la resta tiene que pasar antes de agrupar:

| Campo | Es |
|---|---|
| `cycle_time_ms` | `completed_at − created_at` |
| `assignment_latency_ms` | `started_at − created_at` |

Se pueden **filtrar y agregar**. Filtrarlos es cómo aplican su propio umbral de SLA sin que el
backend sepa cuál es. Son `null` en órdenes que no llegaron a esa etapa, y `avg` ignora los nulos.

### Límites

| Límite | Valor |
|---|---|
| Filtros por request | 10 |
| Claves de `group_by` | 3 |
| Filas — modo agregado | 100 por defecto, 1000 máx |
| Filas — modo documento | 25 por defecto, 100 máx |
| Ventana obligatoria en `orders` | sí, máximo 92 días |
| Tiempo de ejecución | 10 s |

> **`orders` exige una ventana de fechas; `positions`, `products` y `vehicles` no.**
>
> `orders` crece sin límite, así que una consulta sin filtro podría recorrer todo el histórico.
> Las otras tienen el tamaño del depósito. Y hay una razón de correctitud: el stock es la suma
> sobre **todas** las posiciones — una ventana de fechas descartaría en silencio todo lo guardado
> antes, y les devolveríamos un stock más chico sin ningún error.

---

## 6. Recetario — las 21 métricas

`{VENTANA}` = los dos filtros de `created_at` del ejemplo de arriba.

### Rovers (vía `/metrics/query`)

**1 · Histórico de fallas por rover**
```json
{"metric":"wh.vehicle.transitions","from":"…","to":"…","step":"1h",
 "agg":"increase","group_by":["vehicle_id"],"filters":{"to":"ERROR"}}
```
Cada punto son las fallas de ese rover en esa hora. Grafíquenlo tal cual.

**2 · Pareto de fallas por categoría**
```json
{"metric":"wh.vehicle.transitions","from":"…","to":"…","step":"6h",
 "agg":"increase","group_by":["category"],"filters":{"to":"ERROR"}}
```
> Hoy `category` es siempre `UNCATEGORIZED` — el dominio tiene un único estado `ERROR` sin
> taxonomía. La etiqueta ya está publicada, así que cuando el equipo de rovers acuerde una lista
> de códigos, el Pareto se llena solo **sin cambios de su lado**. Mientras tanto es una sola barra.

**3 · MTBF (tiempo promedio entre fallas)**

Backend: las fallas. Ustedes: la división.
```json
{"metric":"wh.vehicle.transitions","from":"…","to":"…","step":"6h",
 "agg":"increase","group_by":["vehicle_id"],"filters":{"to":"ERROR"}}
```
```js
const fallas = serie.points.reduce((a, [, v]) => a + v, 0);
const mtbf   = fallas > 0 ? ventanaEnSegundos / fallas : null;   // null = no falló
```

**4 · MTTR (tiempo promedio de recuperación)**

Dos llamadas. La de arriba, más la fracción de tiempo en ERROR:
```json
{"metric":"wh.vehicle.state","from":"…","to":"…","step":"6h",
 "agg":"avg","group_by":["vehicle_id"],"filters":{"state":"ERROR"}}
```
```js
const fraccion = puntos.reduce((a, [, v]) => a + v, 0) / puntos.length;  // 0..1
const mttr     = fallas > 0 ? (fraccion * ventanaEnSegundos) / fallas : null;
```
Tiempo total en falla dividido por cantidad de fallas.

**5 · Rovers activos simultáneamente**
```json
{"metric":"wh.vehicle.state","from":"…","to":"…","step":"1h",
 "agg":"count","filters":{"state":"BUSY"}}
```
Sin `group_by` para el total de la flota. Recuerden que da decimales con `step` grande.

**Extra · Batería por rover**
```json
{"metric":"wh.vehicle.battery","from":"…","to":"…","step":"1h",
 "agg":"avg","group_by":["vehicle_id"]}
```

### Pedidos (vía `/query/orders`)

**6 · Top SKUs**
```json
{"filters":[{VENTANA}],"unwind":"items",
 "group_by":[{"field":"items.sku","as":"sku"}],
 "aggregates":[{"op":"sum","field":"items.quantity","as":"units"}],
 "sort":[{"field":"units","dir":"desc"}],"size":20}
```

**7 · Pedidos completados y totales del período** · **8 · % de cumplimiento**
```json
{"filters":[{VENTANA}],
 "group_by":[{"field":"status","as":"status"}],
 "aggregates":[{"op":"count","as":"orders"}]}
```
Una sola llamada da ambas: `completados / total`.

**9 · Pedidos por hora (completados vs cancelados)** — y la mitad de "actividad de flota 24h"
```json
{"filters":[{VENTANA}],
 "group_by":[{"field":"created_at","bucket":"hour","as":"hour"},
             {"field":"status","as":"status"}],
 "aggregates":[{"op":"count","as":"orders"}],"size":500}
```
`hour` viene como `"2026-08-01T19:00:00"` en hora local. La otra mitad del gráfico es la métrica 5.

**10 · Tasa de cumplimiento por día** — igual pero `"bucket":"day"` → `"2026-08-01"`.

**11 · Productividad por rover**
```json
{"filters":[{VENTANA},{"field":"assigned_vehicle_id","op":"exists","value":true}],
 "group_by":[{"field":"assigned_vehicle_id","as":"vehicle"}],
 "aggregates":[{"op":"count","as":"orders"}]}
```

**12 · Cycle time promedio**
```json
{"filters":[{VENTANA}],
 "group_by":[{"field":"status","as":"status"}],
 "aggregates":[{"op":"avg","field":"cycle_time_ms","as":"avg_cycle_ms"}]}
```

**13 · Tiempo hasta asignación de vehículo** — igual con `assignment_latency_ms`.

**14 · SLA compliance %**

Dos llamadas: con umbral y sin. El umbral lo eligen ustedes.
```json
{"filters":[{VENTANA},{"field":"cycle_time_ms","op":"lte","value":28800000}],
 "group_by":[{"field":"status","as":"status"}],
 "aggregates":[{"op":"count","as":"n"}]}
```
`dentro_de_sla / total`.

**15 · Eficiencia de picking**
```json
{"filters":[{VENTANA}],"unwind":"items",
 "group_by":[{"field":"created_at","bucket":"day","as":"day"}],
 "aggregates":[{"op":"count","as":"lines"},{"op":"sum","field":"items.quantity","as":"units"}]}
```

### Inventario y demanda

Estas se arman con **dos o tres llamadas cruzadas por ustedes**. Agrupen la demanda por
`items.product_id` — así cruza directo con `positions.product_id`, sin buscar el SKU aparte.

**16 · Demanda diaria promedio por SKU** · **17 · Última vez que se pidió cada SKU** · **20 · Top rotación**
```json
POST /query/orders
{"filters":[{VENTANA}],"unwind":"items",
 "group_by":[{"field":"items.product_id","as":"product_id"}],
 "aggregates":[{"op":"sum","field":"items.quantity","as":"units"},
               {"op":"max","field":"created_at","as":"last_ordered"}],
 "size":500}
```
Demanda diaria = `units / días_de_la_ventana`. Top rotación = ordenar por `units`.

**Stock actual por producto** — sin filtro de fecha:
```json
POST /query/positions
{"group_by":[{"field":"product_id","as":"product_id"}],
 "aggregates":[{"op":"sum","field":"current_stock","as":"on_hand"}],"size":500}
```

**Stock mínimo por producto**:
```json
POST /query/products
{"group_by":[{"field":"id","as":"product_id"}],
 "aggregates":[{"op":"sum","field":"minimum_stock","as":"min_stock"}],"size":500}
```

Con esas tres respuestas:

| Métrica | Cálculo |
|---|---|
| **19 · Duración del stock (días a quiebre)** | `on_hand / (units / días)` |
| **18 · Cobertura promedio** | promedio de lo anterior |
| **18 · SKUs en riesgo** | días a quiebre < su umbral |
| **18 · Dead stock** | `on_hand > 0` y sin demanda en la ventana |
| **21 · Reposición requerida** | `on_hand < min_stock` |

**Extra · Utilización por zona**
```json
POST /query/positions
{"group_by":[{"field":"id_zone","as":"zone"}],
 "aggregates":[{"op":"sum","field":"current_stock","as":"stock"},
               {"op":"sum","field":"maximum_capacity","as":"capacity"}]}
```

---

## 7. Errores

Siempre con la misma forma:

```json
{"error": {"code": "UNBOUNDED_RANGE",
           "message": "Una consulta agrupada debe acotarse con un filtro de fecha (ej: created_at >=)."}}
```

Guíense por `code`; `message` es para mostrar.

| Código | Qué pasó |
|---|---|
| `UNKNOWN_ENTITY` | La entidad no existe **o no es visible para su rol** |
| `UNKNOWN_FIELD` | El campo no existe o no es consultable así |
| `UNSUPPORTED_OPERATOR` | Operador no permitido para ese campo |
| `UNSUPPORTED_AGGREGATION` | Agregación inválida para ese campo o tipo de métrica |
| `UNSUPPORTED_BUCKET` | Bucket inválido, o sobre un campo que no es fecha |
| `UNBOUNDED_RANGE` | Falta el filtro de fecha en una agregación de `orders` |
| `QUERY_TOO_BROAD` | Ventana, claves o step fuera de límite |
| `UNWIND_REQUIRED` | Usaron `items.*` sin `unwind` |
| `NO_AGGREGATES` | `group_by` sin ningún agregado |
| `INVALID_ALIAS` | Alias inválido o repetido |
| `UNKNOWN_TIMEZONE` | Zona horaria inexistente |
| `TOO_MANY_FILTERS` | Más de 10 filtros |
| `INVALID_FILTER_VALUE` | Valor con formato incorrecto |
| `METRICS_UNAVAILABLE` | **503** — VictoriaMetrics caído |
| `UNKNOWN_METRIC` / `UNKNOWN_DIMENSION` | No está en el catálogo de métricas |

**`METRICS_UNAVAILABLE` es el único que no es culpa del request.** Si VictoriaMetrics se cae,
`/metrics/query` responde 503 y `/query/*` sigue funcionando normal. Degraden los gráficos de
rovers y dejen el resto del dashboard vivo.

---

## 8. Datos de demo

Levantando el stack con `SEED_DEMO=true` sobre una base vacía obtienen:

- 13 usuarios, 24 productos, 35 posiciones, 6 rovers, 25 órdenes (~3 semanas)
- **7 días de historial de flota ya cargado** — 44 series

Ese último punto importa: el almacén de métricas no tiene backfill, así que sin la semilla los
gráficos de rovers arrancarían vacíos. Con ella tienen datos para graficar desde el minuto cero,
incluyendo un rover parado toda la semana y otro que falla periódicamente, para que "rovers
activos" y MTBF muestren algo real.

Todas las cuentas usan `Demo1234!`.

---

## 9. Verificación

En el repo hay un script que ejercita **todo lo de esta guía** por HTTP, autenticado como
`DASHBOARD`, sin tocar la base:

```bash
BASE_URL=http://localhost:8080 ./scripts/blackbox-dashboard.sh
```

39 chequeos: descubrimiento, las 21 métricas, los cruces de inventario, la zona horaria y los
límites de seguridad. Si les da 39/39, el backend está listo y cualquier problema está del lado
del dashboard. Si algo falla, mándennos la línea y lo miramos.

---

## 10. Cosas que conviene saber antes de empezar

1. **Empiecen por los catálogos.** Están pensados para eso: no hardcodeen listas de campos.
2. **La zona horaria por defecto es Buenos Aires.** Si agrupan por hora o día y comparan contra
   otra fuente, verifiquen que la otra fuente no esté en UTC — se corre 3 horas.
3. **`count` sobre `wh.vehicle.state` da decimales** con `step` grande. Es correcto: es el
   promedio de rovers en ese estado durante el bloque.
4. **Las métricas de rovers llegan hasta 30 días atrás.** Las de negocio leen todo el histórico.
5. **`orders` necesita ventana de fechas, `positions` no.** No agreguen un filtro de fecha a
   `positions` "por las dudas": se perderían el stock más viejo.
6. **Sin rate limiting todavía.** Si van a hacer polling, algo del orden de 10–30 segundos está
   bien. Si necesitan tiempo real, hablemos: el servidor es compartido.
7. **Cualquier campo que necesiten y no esté**, pídanlo. Agregar uno al catálogo es una línea.
