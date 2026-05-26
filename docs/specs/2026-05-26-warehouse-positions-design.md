# Warehouse Module + Product Location Design

**Date:** 2026-05-26  
**Branch:** `feature/position-endpoints`  
**Author:** Mateo Urrutia  
**Status:** Approved — ready for implementation

---

## 1. Context & Scope

This spec covers all changes required to implement the Warehouse module (Zones, Lines, Positions) and
migrate the Product stock/location model to be position-driven.

The Vehicle endpoints (`GET /vehicles/:id`, `POST /vehicles`) are out of scope — handled in a separate branch.

### What this branch delivers

1. Zone / Line / Position domain entities + full CRUD endpoints
2. Position owns the product-assignment (replaces Product location fields)
3. Per-position stock tracking (`current_stock` on Position)
4. `GET /products/:id/location` — list of positions where a product lives
5. Strip location and stock fields from Product entity (stock is now computed)
6. Refactor `ProductService` — compute `available_stock` and `reserved_stock` via MongoDB aggregation
7. Refactor `OrderService` — remove stock field writes; drain position stock on order completion
8. Update stock alert trigger to use computed stock
9. Update `DataInitializer` to seed warehouse structure

---

## 2. Data Model

### 2.1 Zone (`zones` collection)

| JSON field | Java field | Type | Notes |
|---|---|---|---|
| `id_zone` | `id` | String (UUID) | MongoDB `@Id` |
| `zone_code` | `zoneCode` | String | Unique. e.g. "A", "B" |
| `is_active` | `isActive` | boolean | Defaults to `false` on create |
| `max_allowed_lines` | `maxAllowedLines` | int | Max lines allowed in this zone |
| `created_at` | `createdAt` | Instant | Set on create |

### 2.2 Line (`lines` collection)

| JSON field | Java field | Type | Notes |
|---|---|---|---|
| `id_line` | `id` | String (UUID) | MongoDB `@Id` |
| `id_zone` | `idZone` | String | Reference to Zone |
| `number_line` | `numberLine` | int | Unique within zone. e.g. 1, 2 |
| `is_active` | `isActive` | boolean | Defaults to `false` on create |
| `max_allowed_positions` | `maxAllowedPositions` | int | Max positions in this line |
| `created_at` | `createdAt` | Instant | Set on create |

### 2.3 Position (`positions` collection)

| JSON field | Java field | Type | Notes |
|---|---|---|---|
| `id_position` | `id` | String (UUID) | MongoDB `@Id` |
| `id_line` | `idLine` | String | Reference to Line |
| `id_zone` | `idZone` | String | Denormalized from parent Line on create |
| `position_name` | `positionName` | String | e.g. "P01", "P02" |
| `is_active` | `isActive` | boolean | Defaults to `false` on create |
| `maximum_capacity` | `maximumCapacity` | int | Max units this position can hold |
| `size_stock_to_save` | `sizeStockToSave` | enum | `pequeño` \| `mediano` \| `grande` |
| `product_id` | `productId` | String (UUID) | Nullable — source of truth for assignment |
| `current_stock` | `currentStock` | int | Units currently stored here. Defaults to 0 |
| `created_at` | `createdAt` | Instant | Set on create |

**`product_id` is the single source of truth for product-position assignment.**  
A position holds at most one product type. A product may appear in multiple positions.

### 2.4 Product entity changes

**Remove fields:** `availableStock`, `reservedStock`, `zone`, `line`, `position`, `height`  
**Keep:** `minimumStock` (configured alert threshold — not a count)

Stock is no longer stored on the product. It is always computed:
- `available_stock` = `SUM(positions.current_stock WHERE product_id = this product)`
- `reserved_stock` = `SUM(order_items.quantity WHERE product_id = this AND order.status IN [pending, in_progress])`

---

## 3. API Endpoints

All warehouse endpoints require `ADMIN_WAREHOUSE` role.

### 3.1 Zones — `ZoneController` (`/warehouse/zones`)

| Method | Path | Description | Response |
|---|---|---|---|
| GET | `/warehouse/zones` | List all zones | 200 `{ zones: [...] }` |
| POST | `/warehouse/zones` | Create zone | 201 Zone object |
| PATCH | `/warehouse/zones/:id` | Update zone | 200 Zone object |
| DELETE | `/warehouse/zones/:id` | Soft delete (`is_active=false`) | 204 |

**Soft-delete cascade policy:** Deleting a zone, line, or position only soft-deletes that entity. Children are **not** automatically cascaded — they remain in their current state. Admins are responsible for cleaning up children manually.

**POST request body:**
```json
{
  "zone_code": "A",
  "max_allowed_lines": 10
}
```
`is_active` is always `false` on creation.

### 3.2 Lines — `LineController` (`/warehouse/...`)

| Method | Path | Description | Response |
|---|---|---|---|
| GET | `/warehouse/zones/:id/lines` | Lines in a zone | 200 `{ lines: [...] }` |
| POST | `/warehouse/zones/:id/lines` | Create line in zone | 201 Line object |
| PATCH | `/warehouse/lines/:id` | Update line | 200 Line object |
| DELETE | `/warehouse/lines/:id` | Soft delete | 204 |

**POST request body:**
```json
{
  "number_line": 1,
  "max_allowed_positions": 20
}
```
`id_zone` taken from path. `is_active` defaults to `false`.

### 3.3 Positions — `PositionController` (`/warehouse/...`)

| Method | Path | Description | Response |
|---|---|---|---|
| GET | `/warehouse/lines/:id/positions` | Positions in a line | 200 `{ positions: [...] }` |
| GET | `/warehouse/positions/:id` | Single position detail | 200 Position + `assigned_product` |
| POST | `/warehouse/lines/:id/positions` | Create position in line | 201 Position object |
| PATCH | `/warehouse/positions/:id` | Update position / assign product | 200 Position object |
| DELETE | `/warehouse/positions/:id` | Soft delete | 204 |

**POST request body:**
```json
{
  "position_name": "P01",
  "maximum_capacity": 150,
  "size_stock_to_save": "mediano"
}
```
`id_line` and `id_zone` resolved from parent Line. `is_active` defaults to `false`. `current_stock` defaults to `0`.

**PATCH request body (all fields optional):**
```json
{
  "position_name": "P01",
  "is_active": true,
  "maximum_capacity": 150,
  "size_stock_to_save": "grande",
  "product_id": "uuid-or-null",
  "current_stock": 100
}
```
Setting `product_id: null` explicitly **unassigns** the current product from this position (`current_stock` is also reset to 0).  
To reassign a position to a different product: first PATCH with `product_id: null`, then PATCH again with the new `product_id`. Two explicit calls — no silent displacement.

**GET `/warehouse/positions/:id` response:**
```json
{
  "id_position": "uuid",
  "id_line": "uuid",
  "id_zone": "uuid",
  "position_name": "P01",
  "is_active": true,
  "maximum_capacity": 150,
  "size_stock_to_save": "mediano",
  "product_id": "uuid",
  "current_stock": 100,
  "assigned_product": {
    "id": "uuid",
    "sku": "SKU-ABC-123",
    "name": "Chupetin Bazooka"
  }
}
```
`assigned_product` is `null` if `product_id` is null.

### 3.4 Product location endpoints (added to `ProductController`)

| Method | Path | Description | Response |
|---|---|---|---|
| GET | `/products/:id/location` | All positions where this product lives | 200 `{ locations: [...] }` |

**GET `/products/:id/location` response:**
```json
{
  "locations": [
    {
      "id_position": "uuid",
      "position_name": "P01",
      "current_stock": 100,
      "id_line": "uuid",
      "number_line": 1,
      "id_zone": "uuid",
      "zone_code": "A"
    }
  ]
}
```
Returns `{ "locations": [] }` if the product has no assigned positions.

**`ProductResponse` changes:**
- Remove `location` field entirely
- `stock` object becomes:
```json
"stock": {
  "available": 300,
  "reserved": 50,
  "min": 20
}
```
Where `available` and `reserved` are always computed, `min` is stored on the product.

**`CreateProductRequest` / `UpdateProductRequest` changes:**
- Remove `zone`, `line`, `position`, `height` fields
- Remove `available_stock` field (stock lives in positions now)
- **Keep** `minimum_stock` — it is still stored on the product and settable on create/update

**Product soft-delete side effect:** when `DELETE /products/:id` is called, all positions where `product_id = this product` are cleared (`product_id → null`, `current_stock → 0`). This prevents ghost assignments on inactive products.

---

## 4. Error Codes

| HTTP | Code | Trigger |
|---|---|---|
| 404 | `ZONE_NOT_FOUND` | Zone ID doesn't exist |
| 404 | `LINE_NOT_FOUND` | Line ID doesn't exist |
| 404 | `POSITION_NOT_FOUND` | Position ID doesn't exist |
| 409 | `ZONE_CODE_ALREADY_EXISTS` | Duplicate `zone_code` on POST /warehouse/zones |
| 409 | `LINE_NUMBER_ALREADY_EXISTS` | Duplicate `number_line` within same zone |
| 409 | `POSITION_ALREADY_OCCUPIED` | PATCH position — `product_id` already set to a different product |
| 400 | `STOCK_EXCEEDS_CAPACITY` | `current_stock` > `maximum_capacity` on any position write |

---

## 5. Service Layer

### 5.1 New services

**`ZoneService`**
- CRUD operations for zones
- Validates `zone_code` uniqueness on create

**`LineService`**
- CRUD operations for lines
- Validates `number_line` uniqueness within its zone on create
- Resolves parent Zone on create to populate `id_zone`

**`PositionService`**
- CRUD operations for positions
- Resolves parent Line → `id_zone` on create
- `POSITION_ALREADY_OCCUPIED` guard: reject if `product_id` already set to a different product
- `STOCK_EXCEEDS_CAPACITY` guard: reject if `current_stock > maximum_capacity`
- `current_stock` floor: reject if `current_stock < 0`
- `assigned_product` lookup on `GET /warehouse/positions/:id`: query products where `id = position.productId`

### 5.2 Updated: ProductService

**`getProductLocation(productId)`**
- Query positions collection for all documents where `product_id = productId`
- Join line and zone info per result
- Return list (empty list if none)

**`getProducts()` / `getProduct()`**
- Use `MongoTemplate` aggregation pipeline:
  1. `$match` filters (category, search, is_active)
  2. `$lookup` positions → group by product, sum `current_stock` → `available_stock`
  3. `$lookup` orders filtered by `status IN [pending, in_progress]` → sum quantities → `reserved_stock`
- Single DB round-trip instead of N+1 queries

### 5.3 Updated: OrderService

**Order creation:**
- Compute available stock via aggregation (inside a MongoDB transaction)
- No longer reads or writes `product.availableStock` or `product.reservedStock`
- Stock check: `available_stock - reserved_stock >= requested_quantity`

**Order completion (triggered by Central via Kafka `order.status = completed`):**
- Wrapped in `@Transactional`
- FIFO stock depletion: sort positions by `created_at` ASC, drain `current_stock` until order quantity is satisfied
- ⚠️ Known limitation: FIFO is a best-effort accounting approximation. Actual rover pick order may differ from the system's depletion order. `current_stock` per position may drift from physical reality over time.
- After depletion: check if any product's computed `available_stock` dropped below `minimumStock` → publish `stock.alert` WebSocket event if so

### 5.4 Updated: StockAlertPublisher

- Trigger condition moves from "after order create" to "after order complete"
- Computed stock (SUM of position stocks) is compared against `product.minimumStock`

---

## 6. Infrastructure Changes

### 6.1 MongoDB Replica Set (required for transactions)

Multi-document transactions require MongoDB to run as a replica set.

**docker-compose.yml change:**
```yaml
mongodb:
  command: ["--replSet", "rs0"]
```

**One-time init on first boot** (handled in `DataInitializer` or a startup script):
```javascript
rs.initiate()
```

**Spring config:** add `MongoTransactionManager` bean to `MongoConfig`:
```java
@Bean
MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
    return new MongoTransactionManager(dbFactory);
}
```

### 6.2 DataInitializer update

Seed order:
1. Create zones (A, B)
2. Create lines per zone
3. Create positions per line (with `size_stock_to_save`, `maximum_capacity`)
4. Assign products to positions via `product_id` + `current_stock`
5. Remove stock seeding from product creation

---

## 7. Controller Structure

```
api/warehouse/
  zone/
    ZoneController.java
    ZoneResponse.java
    CreateZoneRequest.java
    UpdateZoneRequest.java
  line/
    LineController.java
    LineResponse.java
    CreateLineRequest.java
    UpdateLineRequest.java
  position/
    PositionController.java
    PositionResponse.java
    PositionDetailResponse.java   ← includes assigned_product
    CreatePositionRequest.java
    UpdatePositionRequest.java
```

New exceptions (following existing pattern in `service/exception/`):
```
ZoneNotFoundException.java
LineNotFoundException.java
PositionNotFoundException.java
ZoneCodeAlreadyExistsException.java
LineNumberAlreadyExistsException.java
PositionAlreadyOccupiedException.java
StockExceedsCapacityException.java
```

---

## 8. Code Quality Improvements (included in this branch)

### 8.1 Bean Validation on all request classes

All new request records (`CreateZoneRequest`, `CreateLineRequest`, `CreatePositionRequest`, `UpdatePositionRequest`, etc.) and existing product/order request classes must use Jakarta Bean Validation annotations:

```java
public record CreateZoneRequest(
    @NotBlank String zone_code,
    @Min(1) int max_allowed_lines
) {}
```

Controllers use `@Valid @RequestBody` on every endpoint. The existing `GlobalExceptionHandler` already handles `MethodArgumentNotValidException` → `400 Bad Request`. No new infrastructure needed.

Apply to: all new request classes **and** existing `CreateProductRequest`, `UpdateProductRequest`, `CreateOrderRequest`.

### 8.2 Global Jackson snake_case config

Replace all individual `@JsonProperty` annotations with a single global Jackson configuration:

```java
// JacksonConfig.java (new file)
@Configuration
public class JacksonConfig {
    @Bean
    Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
        return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    }
}
```

After adding this config, remove all `@JsonProperty` annotations from existing and new request/response classes.

**Regression check required:** run the full test suite and manually verify all existing endpoints still return correct snake_case field names before merging.

---

## 9. Out of Scope

- Vehicle endpoints (`GET /vehicles/:id`, `POST /vehicles`) — separate branch
- `POST /auth/refresh` — marked TBD in RFC
- Multi-location route optimization for rovers (Central's responsibility)
- Real-time per-position stock sync with rover telemetry
