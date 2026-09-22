# RFC — Métricas calculadas (contrato común) y sugerencia de reposición

> **Grupo 4 — Backend y API**
>
> Extiende el [`RFC_SmartWarehouse_Backend.md`](./RFC_SmartWarehouse_Backend.md) y el [`RFC_Restock_Recepcion.md`](./RFC_Restock_Recepcion.md). Define un contrato común para las métricas de negocio que calcula el backend, e implementa la primera: **demanda diaria ponderada y sugerencia de reposición**.

---

## 0. Carátula

| Revisor | Estado |
|---|---|
| Mateo Urrutia | En progreso |

---

## 1. Objetivo

Cada vez más equipos consumen métricas. Hoy cada uno cruza datos por su cuenta (órdenes, stock, restock) y aplica sus fórmulas, así que dos equipos pueden llegar a números distintos para lo mismo. Este RFC:

1. Define un **contrato REST común** para métricas calculadas: el equipo manda los parámetros y el backend guarda la lógica y hace el cálculo.
2. Implementa la primera métrica con ese contrato: `POST /metrics/restock-suggestions`.
3. Ajusta el seeding para que la métrica tenga un año de historia y resultados variados el día de la demo.

## 2. Alcance

**Incluido:** contrato común (request, respuesta, errores, catálogo), la métrica de sugerencia de reposición con las fórmulas de la sección 4, y los cambios de seeding de la sección 7.

**Fuera de alcance:**
- Guardar parámetros en el backend (defaults, overrides por producto). Hoy **los manda cada equipo en cada request**.
- Crear la `RestockOrder` automáticamente a partir de la sugerencia. La métrica solo sugiere.
- Ciclo de estados de `RestockOrder` (cancelar un pedido que nunca va a llegar). Ver 4.3.
- Series de tiempo de demanda. Para graficar demanda diaria ya existe `POST /query/orders` con `unwind: items` (ver `DASHBOARD_INTEGRATION.md`).

## 3. Estado actual

| Mecanismo | Qué sirve | Por qué no alcanza |
|---|---|---|
| `GET /metrics/catalog` + `POST /metrics/query` | Series crudas de la flota (VictoriaMetrics) | `MetricRegistry` guarda solo señales crudas. Una métrica **derivada** con parámetros de negocio no encaja ahí. |
| `POST /query/{entity}` | Consultas genéricas sobre Mongo | Es solo para el dashboard y devuelve datos, no cálculos. Cada equipo tendría que reimplementar las fórmulas. |

## 4. Métrica: demanda ponderada y sugerencia de reposición

### 4.1. Fórmulas

```
Demanda de largo plazo = unidades pedidas en [ahora − período largo, ahora − período reciente) / (período largo − período reciente)
Demanda reciente       = unidades pedidas en [ahora − período reciente, ahora) / período reciente
Demanda combinada      = α × Demanda reciente + (1 − α) × Demanda de largo plazo

Stock de seguridad  = Demanda de largo plazo × días de seguridad
Punto de reposición = Demanda combinada × Lead time + Stock de seguridad
Stock objetivo      = Demanda combinada × (Lead time + Cobertura) + Stock de seguridad

Posición de inventario = Stock disponible + Stock en pedido
Cantidad sugerida      = ⌈ máx(0, Stock objetivo − Posición de inventario) ⌉

Decisión: si Posición de inventario ≤ Punto de reposición → sugerir reposición por Cantidad sugerida
          si no → Cantidad sugerida = 0
```

La ventana larga **excluye** la ventana reciente, así ningún día cuenta dos veces. Por eso el divisor es `período largo − período reciente`: con 60 y 7 son 53 días. Las ventanas se cuentan hacia atrás desde el momento del request (rolling), no por días calendario.

### 4.2. Definiciones exactas (qué dato del backend es cada término)

| Término | Fuente |
|---|---|
| **Unidades pedidas** | Suma de `items[].quantity` del producto en órdenes con `status ≠ CANCELLED`, contadas por `createdAt`. Medimos la demanda cuando se pide, no cuando se despacha: así las órdenes PENDING de los últimos días ya cuentan como demanda reciente. |
| **Stock disponible** | `Σ Position.currentStock` (posiciones activas) **−** `Σ items[].quantity` de órdenes `PENDING`/`IN_PROGRESS`. Es el mismo `stock.available` que devuelve `GET /products`. |
| **Stock en pedido** | Suma, sobre las `RestockOrder` del producto, de `máx(0, quantity_requested − Σ Reception.quantity_received vinculadas)`. Es lo pedido al proveedor que todavía no llegó. |

> **Por qué la Posición de inventario no resta el stock reservado.** El Stock disponible ya lo descuenta. Si además se restara el reservado, las unidades comprometidas se descontarían dos veces. Esta es la corrección al documento original, que usaba `Disponible + En pedido − Reservado`.

### 4.3. Limitación conocida: pedidos que nunca se completan

`RestockOrder` no tiene estado. Un pedido que el proveedor entregó de menos, o que nunca entregó, sigue sumando como *en pedido* para siempre. Mientras no exista una forma de cerrar o cancelar un pedido (queda fuera de alcance, ver `RFC_Restock_Recepcion.md` §2), un pedido abandonado frena las sugerencias para ese producto. Si en la práctica pasa, el siguiente paso es agregar ese estado, no parchear la fórmula.

### 4.4. Ejemplo (el del documento de negocio, con la fórmula corregida)

Parámetros: α=0,3 · período reciente=7 · período largo=60 · días de seguridad=2 · lead time=5 · cobertura=7.
Datos: Demanda de largo plazo=22 u/día · Demanda reciente=25 u/día · **Stock disponible=140** (neto de reservas).

| Cálculo | Resultado |
|---|---|
| Demanda combinada = 0,3×25 + 0,7×22 | 22,90 |
| Stock de seguridad = 22×2 | 44,00 |
| Punto de reposición = 22,9×5 + 44 | 158,50 |
| Stock objetivo = 22,9×12 + 44 | 318,80 |

| Escenario | Posición de inventario | ¿≤ 158,5? | Cantidad sugerida |
|---|---|---|---|
| 1 — nada en camino (en pedido=0) | 140 + 0 = **140** | Sí | ⌈318,8 − 140⌉ = **179** |
| 2 — 50 u en camino (en pedido=50) | 140 + 50 = **190** | No | 0 |

Este ejemplo es un test unitario del cálculo (ver §8).

## 5. Contrato común de métricas calculadas

Toda métrica calculada, esta y las que vengan, respeta este contrato.

### 5.1. Request

`POST /metrics/{nombre-de-la-métrica}` con body JSON en snake_case:

```json
{
  "params":  { "...": "parámetros de negocio de la métrica, todos obligatorios" },
  "filters": { "...": "acotan el universo (opcionales)" }
}
```

- **POST**, aunque sea una lectura: los parámetros son estructurados y así coincide con `POST /metrics/query`. El cálculo no modifica nada y es idempotente.
- **Los `params` son obligatorios.** El backend no completa defaults: cada equipo decide sus parámetros de negocio y los envía.

### 5.2. Respuesta

```json
{
  "metric": "restock_suggestions",
  "params_used": { "...": "eco de los params validados" },
  "generated_at": "2026-09-22T15:04:05Z",
  "data": [ { "...": "una fila por entidad" } ]
}
```

`params_used` y `generated_at` permiten que un gráfico diga con qué parámetros y en qué momento se calculó.

### 5.3. Errores

Mismo formato que el resto de la API: `{"error": {"code", "message"}}`.

| Código | HTTP | Cuándo |
|---|---|---|
| `INVALID_METRIC_PARAMS` | 400 | Falta un parámetro, está fuera de rango o hay una combinación inválida (por ejemplo, período largo ≤ período reciente). |
| `VALIDATION_ERROR` / `BAD_REQUEST` | 400 | Body malformado. Lo manejan los handlers existentes. |
| `ACCESS_DENIED` | 403 | Rol sin acceso. |

### 5.4. Catálogo

`GET /metrics/catalog` suma el campo `computed_metrics`, sin tocar `metrics`, así que los consumidores actuales no se rompen. Cada entrada describe la métrica con lo necesario para armar el request:

```json
{
  "name": "restock_suggestions",
  "path": "/metrics/restock-suggestions",
  "display_name": "Sugerencia de reposición",
  "params": [
    { "name": "alpha", "type": "number", "min": 0, "max": 1, "description": "..." }
  ],
  "filters": [ { "name": "product_ids", "type": "string[]", "description": "..." } ]
}
```

### 5.5. Roles

Los mismos que `/metrics/*`: `SUPERADMIN`, `ADMIN_SYSTEM`, `ADMIN_WAREHOUSE`, `DASHBOARD`.

## 6. `POST /metrics/restock-suggestions`

### 6.1. Request

```json
{
  "params": {
    "alpha": 0.3,
    "recent_days": 7,
    "long_days": 60,
    "safety_days": 2,
    "lead_time_days": 5,
    "coverage_days": 7
  },
  "filters": {
    "product_ids": ["p-001", "p-002"],
    "category": "..."
  }
}
```

| Param | Tipo | Rango | Controla |
|---|---|---|---|
| `alpha` | número | 0 – 1 | Peso de la demanda reciente frente a la de largo plazo |
| `recent_days` | entero | 1 – 364 | Duración del período reciente |
| `long_days` | entero | `recent_days + 1` – 365 | Duración del período largo (incluye el reciente, que se excluye del promedio) |
| `safety_days` | número | ≥ 0 | Tamaño del colchón de seguridad |
| `lead_time_days` | número | ≥ 0 | Días que tarda en llegar una reposición |
| `coverage_days` | número | ≥ 0 | Días de stock que se busca cubrir después de reponer |

Filtros opcionales: sin filtros, se calcula para **todos los productos activos**. `product_ids` y `category` se combinan con AND.

### 6.2. Respuesta — una fila por producto

```json
{
  "product_id": "p-001",
  "sku": "ELEC-001",
  "name": "...",
  "long_term_demand": 22.0,
  "recent_demand": 25.0,
  "blended_demand": 22.9,
  "safety_stock": 44.0,
  "reorder_point": 158.5,
  "target_stock": 318.8,
  "available_stock": 140,
  "on_order_stock": 0,
  "inventory_position": 140,
  "should_restock": true,
  "suggested_quantity": 179
}
```

- Los valores decimales se redondean a 2 decimales en la respuesta. El cálculo usa la precisión completa.
- `suggested_quantity` es entera y se redondea **hacia arriba**, porque no se piden fracciones y redondear hacia abajo dejaría el stock por debajo del objetivo.
- Orden: primero `should_restock = true`, y dentro de cada grupo por `suggested_quantity` descendente, para que lo más urgente aparezca arriba.

## 7. Seeding: un año de datos que muestre la métrica

El dataset de demo (`DemoDataset`) ya carga un año de órdenes (2 por día, COMPLETED/CANCELLED) y de restock+recepciones (cada 5 días). Hay tres problemas:

1. **Todos los productos quedan igual.** `simulateStock` deja a cada producto con el mismo colchón fijo (`HEALTHY_BUFFER = 30`), y con la demanda seedeada (~0,6 u/día por producto) ninguno dispara reposición. La demo saldría vacía.
2. **Nunca hay stock en pedido.** Cada `RestockOrder` seedeada tiene su recepción completa, así que no se puede ver el Escenario 2.
3. **Pedidos fantasma.** 1 de cada 5 recepciones no se vincula a su `RestockOrder` (a propósito, para mostrar recepciones sin pedido), pero el pedido se crea igual. Con §4.2 esos pedidos contarían como *en pedido* para siempre.

Cambios:

| # | Cambio |
|---|---|
| 7.1 | Las recepciones no vinculadas **no generan** `RestockOrder`: una recepción sin pedido es justamente eso. El conteo de restock orders del histórico baja (≈73 → ≈58). Se actualiza `DASHBOARD_INTEGRATION.md`. |
| 7.2 | El colchón de stock pasa a depender del producto, repartido en tres grupos deterministas, para que con los parámetros del ejemplo (§4.4) aparezcan los tres casos: **(a) dispara** (colchón por debajo del punto de reposición), **(b) salvado por lo que viene en camino** (mismo colchón bajo + una `RestockOrder` reciente sin recepción que lleva la posición por encima del punto de reposición) y **(c) sano** (colchón holgado, como hoy). |
| 7.3 | Se agregan `RestockOrder` recientes (últimos días, sin recepción) para los productos del grupo (b), y una parcialmente recibida para mostrar que se descuenta lo ya recibido. |

Se mantiene todo lo demás: el volumen de órdenes y su distribución en el año no cambian, así que los gráficos existentes no se ven afectados. El producto 0 sigue siendo el caso intencional de *bajo stock sin demanda*: con demanda 0 su punto de reposición es 0 y no dispara.

## 8. Implementación

| Pieza | Ubicación | Qué hace |
|---|---|---|
| `RestockFormula` | `service/metrics/` | Función pura: recibe params + insumos (demandas, disponible, en pedido) y devuelve la fila calculada. Sin I/O. |
| `RestockSuggestionService` | `service/metrics/` | Junta los insumos por producto con agregaciones bulk sobre Mongo (demanda en las dos ventanas, disponible neto, en pedido) y aplica `RestockFormula`. |
| `ComputedMetricDescriptor` + registro | `service/metrics/MetricRegistry` | Segunda lista del mismo registro (`computed()`), que alimenta `computed_metrics` en el catálogo. |
| `RestockSuggestionsRequest` / `ComputedMetricResponse<T>` | `api/metrics/` | Records del request (con Bean Validation) y del sobre común. |
| Endpoint | `MetricsController` | `@PostMapping("/restock-suggestions")`, mismos roles que el controller. |
| `INVALID_METRIC_PARAMS` | `GlobalExceptionHandler.MESSAGES` | Mensaje en español. |

`ProductService` expone sus agregaciones bulk de stock disponible/reservado para no duplicarlas.

**Tests:**
- `RestockFormulaTest`: el ejemplo de §4.4 (escenarios 1 y 2), `alpha` en 0 y en 1, demanda 0.
- `RestockSuggestionServiceTest`: ventanas (un día no cuenta dos veces), órdenes CANCELLED excluidas, en pedido neto de lo recibido y nunca negativo.
- `MetricsControllerTest` / `MetricsControllerSecurityTest`: validación → `INVALID_METRIC_PARAMS`, roles, catálogo con `computed_metrics`.
- `DemoDatasetTest`: con los params de §4.4 aparecen los tres grupos de §7.2 y no hay restock orders huérfanas de §7.1.

**Docs:** `DASHBOARD_INTEGRATION.md` (nueva sección + conteos del seed), colección Bruno `docs/bruno/metrics/restock-suggestions.bru`.

## 9. Decisiones de diseño

| Decisión | Alternativa descartada | Por qué |
|---|---|---|
| Un endpoint por métrica con un sobre común | Un único `POST /metrics/compute {metric, params}` | Cada endpoint queda legible y documentado en OpenAPI con su propio schema. Un motor genérico sería otro mecanismo en paralelo a `/query`. |
| Parámetros por request, obligatorios | Parámetros guardados en el backend | Pedido explícito: cada equipo decide sus parámetros de negocio. Si más adelante se quieren defaults compartidos, se agregan sin romper el contrato. |
| Demanda por `createdAt` de órdenes no canceladas | Por `completedAt` de órdenes COMPLETED | La demanda nace cuando se pide. Contar por despacho retrasa la señal y deja vacía la ventana reciente mientras las órdenes siguen pendientes. |
| Calcular a demanda | Precalcular y guardar | El volumen (decenas de productos, un año de órdenes) se agrega en milisegundos y así el resultado siempre refleja los parámetros enviados. |
