# Pagination — Design Spec
_Date: 2026-05-25_

## Overview

Add offset-based pagination to all four list endpoints (`GET /orders`, `GET /products`, `GET /users`, `GET /vehicles`). Push the two remaining in-memory filters (product text search, order date range) down to MongoDB so pagination counts are always correct. Standardise all list response shapes on a consistent envelope.

---

## 1. API Contract

### New query parameters (all list endpoints)

| Param  | Type | Default | Max | Notes                               |
|--------|------|---------|-----|-------------------------------------|
| `page` | int  | `0`     | —   | Zero-indexed                        |
| `size` | int  | `10`    | `50`| Requests above 50 are silently clamped |

All existing filter parameters (`status`, `from`, `to`, `category`, `search`, `role`, `isActive`, `vehicleId`) are unchanged and continue to work alongside `page`/`size`.

### Standardised response envelope

All four list endpoints return:

```json
{
  "<resource>": [...],
  "pagination": {
    "page": 0,
    "size": 10,
    "total_elements": 143,
    "total_pages": 15
  }
}
```

Where `<resource>` is `orders`, `products`, `users`, or `vehicles`.

This is a **breaking change** for `/users` and `/vehicles`, which currently return a plain `List`. It is **additive** for `/orders` and `/products`, which already use an envelope — `pagination` is simply added alongside the existing data key.

### New shared record

```java
// package: com.usal.whbackend.api
public record Pagination(int page, int size, long total_elements, int total_pages) {
    public static Pagination from(Page<?> p) {
        return new Pagination(p.getNumber(), p.getSize(),
                              p.getTotalElements(), p.getTotalPages());
    }
}
```

---

## 2. Repository Layer

### Users & Vehicles — derived query additions

Add `Pageable`-accepting variants to `UserRepository` (and `VehicleRepository` when it is implemented). Existing `List<T>` methods are kept for any internal non-paginated use.

```java
// UserRepository
Page<User> findByRole(UserRole role, Pageable pageable);
Page<User> findByActive(boolean active, Pageable pageable);
Page<User> findByRoleAndActive(UserRole role, boolean active, Pageable pageable);
// findAll(Pageable) already provided by MongoRepository
```

Spring Data MongoDB generates implementations automatically.

### Products — MongoTemplate dynamic query

`ProductService` receives an injected `MongoTemplate`. The dynamic query replaces both the repository dispatch logic and the in-memory `search` stream filter:

```java
Query query = new Query();
if (category != null) query.addCriteria(Criteria.where("category").is(category));
query.addCriteria(Criteria.where("active").is(active != null ? active : true));
if (search != null && !search.isBlank()) {
    Criteria nameCriteria = Criteria.where("name").regex(search, "i");
    Criteria skuCriteria  = Criteria.where("sku").regex(search, "i");
    query.addCriteria(new Criteria().orOperator(nameCriteria, skuCriteria));
}
long total = mongoTemplate.count(query, Product.class);
List<Product> items = mongoTemplate.find(query.with(pageable), Product.class);
return PageableExecutionUtils.getPage(items, pageable, () -> total);
```

The existing derived methods (`findBySku`, `findByCategoryAndActive`, `updateStock`) on `ProductRepository` are untouched.

### Orders — MongoTemplate dynamic query

`OrderRepository` (the `@Component` wrapper) receives an injected `MongoTemplate`. The in-memory `from`/`to` stream filters are removed and replaced with Criteria:

```java
Query query = new Query();
if (status != null)    query.addCriteria(Criteria.where("status").is(status));
if (vehicleId != null) query.addCriteria(Criteria.where("assignedVehicleId").is(vehicleId));
if (from != null)      query.addCriteria(Criteria.where("createdAt").gte(from));
if (to != null)        query.addCriteria(Criteria.where("createdAt").lte(to));
long total = mongoTemplate.count(query, Order.class);
List<Order> items = mongoTemplate.find(query.with(pageable), Order.class);
return PageableExecutionUtils.getPage(items, pageable, () -> total);
```

The `save`, `cancel`, `findById`, and Kafka publish methods on `OrderRepository` are untouched.

---

## 3. Service Layer

### Signature changes

Each list method gains a `Pageable` parameter and returns `Page<T>`:

```java
// ProductService
public Page<Product> getProducts(String category, String search, Boolean active, Pageable pageable)

// OrderService
public Page<Order> getOrders(String status, String from, String to, String vehicleId, Pageable pageable)

// UserService
public Page<User> getUsers(String role, Boolean isActive, Pageable pageable)

// VehicleService
public Page<Vehicle> getVehicles(Pageable pageable)
```

### Removed in-memory filters

- **`ProductService.getProducts`**: the `stream().filter(...)` block matching name/SKU is deleted (now a MongoDB `$regex` query).
- **`OrderService.getOrders`**: the two `stream().filter(...)` blocks for `from` and `to` are deleted (now MongoDB `gte`/`lte` criteria).

Date parsing (`Instant.parse`) and status parsing (`OrderStatus.valueOf`) remain in `OrderService` — they validate input and throw `400` before the query is built.

All write paths (`createOrder`, `cancelOrder`, `createProduct`, `updateProduct`, `deleteProduct`, `createUser`, `updateUser`, `resetPassword`) are untouched.

---

## 4. Controller Layer

### Query param additions

Each list endpoint adds:

```java
@RequestParam(defaultValue = "0") int page,
@RequestParam(defaultValue = "10") int size
```

Size is clamped before the `PageRequest` is built:

```java
Pageable pageable = PageRequest.of(page, Math.min(size, 50));
```

### Response type

Return types change from `ResponseEntity<Map<String, List<...>>>` / `ResponseEntity<List<...>>` to `ResponseEntity<Map<String, Object>>`:

```java
Page<Product> result = productService.getProducts(category, search, isActive, pageable);
return ResponseEntity.ok(Map.of(
    "products",   result.getContent().stream().map(ProductResponse::from).toList(),
    "pagination", Pagination.from(result)
));
```

### OpenAPI docs

Each list endpoint gets two new `@Parameter` annotations for `page` and `size`. The `200` response description is updated to reference the paginated envelope. No other Swagger changes.

---

## 5. Testing

### Controller tests (`@WebMvcTest`)

- Update existing list-endpoint tests to assert the `pagination` key is present with correct `page`, `size`, `total_elements`, `total_pages`.
- Add cases: default params, explicit `page`/`size`, size clamping (`size=200` → treated as `size=50`).

### Service tests

- Update mocks to return `PageImpl<T>` instead of `List<T>`.
- For `ProductService` and `OrderService`: verify dynamic criteria construction (search present → regex criteria added; no search → no regex criteria).

### Repository / integration tests

- Extend `OrderRepositoryTest` with paginated query cases.
- Add product query test verifying the `MongoTemplate`-based dynamic query against embedded MongoDB (flapdoodle already used in the project).

---

## Out of Scope

- Cursor-based pagination
- Sorting parameters (`sort`, `direction`) — can be added later
- End-to-end / contract tests
- Changes to write paths
