# Ajustes de stock y reconciliación de pedidos

Cuando el stock de una posición **baja** por una vía manual, el backend registra el ajuste y
revisa los pedidos del producto, todo en **una sola transacción** de MongoDB (replica set). Si
algo falla, no queda ni el stock bajado, ni el historial, ni pedidos cancelados a medias.

## Qué dispara la reconciliación

| Vía | Evento registrado |
| --- | --- |
| `POST /warehouse/positions/{id}/stock-adjustments` | `MANUAL_DECREASE` o `DAMAGE` |
| `PATCH /warehouse/positions/{id}` con `current_stock` menor | `MANUAL_DECREASE` |
| `DELETE /products/{id}` con stock en sus posiciones | `UNASSIGNED` (una entrada por posición) |
| `PATCH /warehouse/positions/{id}` con `unassign_product: true` y stock > 0 | `UNASSIGNED` |
| `PATCH` con `is_active: false` / `DELETE /warehouse/positions/{id}` con stock > 0 | `DEACTIVATED` |

Un aumento por `PATCH` también queda en el historial (`MANUAL_INCREASE`) pero no cancela nada. Una
baja en una posición inactiva se registra, pero no reconcilia: ese stock ya no contaba como
disponible. El descuento al completar un pedido (`StockDrainService`) y las recepciones de
proveedor **no** son ajustes y no pasan por acá.

## Reglas de reconciliación

Con el stock físico que queda del producto (suma de sus posiciones activas):

1. Los pedidos **en proceso** (`in_progress`) se respetan: su cantidad se descuenta primero y
   **nunca** se cancelan por esta vía, aunque falte stock.
2. Los pedidos **pendientes** (`pending`) se recorren del más viejo al más nuevo (`created_at`).
3. Si el pedido entra **entero** con lo que queda, se mantiene y reserva su cantidad; si no, se
   cancela **entero** (también sus otros productos) con `cancel_reason`
   `INSUFFICIENT_STOCK: product <sku> needs N units but only M remain available`.

Ejemplo: 14 unidades, se rompen 6 (quedan 8). El pedido en proceso de 5 se respeta (quedan 3). El
pendiente más viejo pide 4: no entra, se cancela. El siguiente pide 2: entra, se mantiene.

Las cancelaciones se publican en el topic `orders` (`status: cancelled`, mismo payload de
siempre) y por WebSocket **recién después del commit**.

## Concurrencia

Dentro de una transacción de Mongo las lecturas salen de un snapshot y solo chocan las escrituras
al mismo documento. Por eso cada reconciliación incrementa un documento guarda por producto
(`stock_guards`): dos reconciliaciones simultáneas del mismo producto no pueden confirmarse las dos,
y la segunda responde `409 STOCK_CHANGE_CONFLICT` sin haber cambiado nada (se puede reintentar). Un
pedido creado en el mismo instante que un ajuste puede quedar evaluado contra el stock anterior: el
alta de pedidos no toma el guarda (hoy serializa solo con candados en memoria), así que esa carrera
sigue acotada a la ventana de dos transacciones solapadas.

El mismo `409 STOCK_CHANGE_CONFLICT` se responde cuando dos cambios simultáneos tocan **la misma
posición** (`POST /warehouse/positions/{id}/stock-adjustments` y `PATCH /warehouse/positions/{id}`):
el segundo choca con un `WriteConflict` de Mongo (código 112, `TransientTransactionError`) al
escribir la posición o al confirmar la transacción. Nunca se responde 500 por esto; el perdedor no
cambió nada y se puede reintentar.

## Endpoints

- `POST /warehouse/positions/{id}/stock-adjustments` (SUPERADMIN, ADMIN_WAREHOUSE): body
  `{ "quantity": 6, "type": "DAMAGE", "reason": "..." }` (`type` opcional, `MANUAL_DECREASE` por
  defecto; `reason` opcional). Valida entero > 0 (`2.5` es 400, no se trunca) y <= stock de la posición; ignora mínimo y
  reservado. Responde `201` con `{ "adjustment": {...}, "cancelled_orders": [...] }`.
- `GET /warehouse/positions/{id}/stock-adjustments` (mismos roles que la posición) y
  `GET /products/{id}/stock-adjustments` (SUPERADMIN, ADMIN_WAREHOUSE, ADMIN_SALES): historial paginado,
  más nuevo primero, `{ "stock_adjustments": [...], "pagination": {...} }`.
- `PATCH /warehouse/positions/{id}`: contrato intacto; acepta `reason` opcional y la respuesta suma
  `cancelled_orders` (siempre presente, `[]` si no hubo).

Colección `stock_adjustments`: `position_id`, `product_id`, `type`, `reason`, `quantity_before`,
`quantity_after`, `adjusted_by` (id de usuario, `system` si no hubo sesión), `created_at`,
`cancelled_order_ids`.

## Compatibilidad con el flujo actual de la web

La web hoy hace el `PATCH` y después recorre los pedidos para cancelar los que no entran. Con este
cambio el backend ya canceló lo que no entraba, así que los pasos 3 a 7 de la web no encuentran
nada para cancelar (`POST /orders/{id}/cancel` sobre uno ya cancelado devuelve 409
`ORDER_NOT_CANCELLABLE`, pero la web ya no lo intenta porque ya no figura pendiente). Para mostrar
el aviso con los pedidos afectados conviene leer `cancelled_orders` de la respuesta.
