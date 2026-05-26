# Warehouse Module + Product Location Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Zone/Line/Position CRUD, migrate product stock to be position-driven, and refactor order stock accounting to use computed values.

**Architecture:** Position is the source of truth for product assignment and stock (`product_id`, `current_stock`). Product stock is always computed from positions (available) and active orders (reserved). Order completion triggers FIFO position stock drain. MongoDB transactions protect multi-document writes.

**Tech Stack:** Spring Boot 4.0.6, Java 21, MongoDB 7, Spring Data MongoDB, MongoTemplate aggregations, `@Transactional` with `MongoTransactionManager`, Jakarta Bean Validation (already in `build.gradle.kts`), Google Java Format (run `./gradlew spotlessApply` before every commit).

---

## File Map

**Create:**
- `src/main/java/com/usal/whbackend/config/JacksonConfig.java`
- `src/main/java/com/usal/whbackend/domain/StockSize.java`
- `src/main/java/com/usal/whbackend/domain/Zone.java`
- `src/main/java/com/usal/whbackend/domain/Line.java`
- `src/main/java/com/usal/whbackend/domain/Position.java`
- `src/main/java/com/usal/whbackend/repository/ZoneRepository.java`
- `src/main/java/com/usal/whbackend/repository/LineRepository.java`
- `src/main/java/com/usal/whbackend/repository/PositionRepository.java`
- `src/main/java/com/usal/whbackend/service/ZoneService.java`
- `src/main/java/com/usal/whbackend/service/LineService.java`
- `src/main/java/com/usal/whbackend/service/PositionService.java`
- `src/main/java/com/usal/whbackend/service/exception/ZoneNotFoundException.java`
- `src/main/java/com/usal/whbackend/service/exception/LineNotFoundException.java`
- `src/main/java/com/usal/whbackend/service/exception/PositionNotFoundException.java`
- `src/main/java/com/usal/whbackend/service/exception/ZoneCodeAlreadyExistsException.java`
- `src/main/java/com/usal/whbackend/service/exception/LineNumberAlreadyExistsException.java`
- `src/main/java/com/usal/whbackend/service/exception/PositionAlreadyOccupiedException.java`
- `src/main/java/com/usal/whbackend/service/exception/StockExceedsCapacityException.java`
- `src/main/java/com/usal/whbackend/api/warehouse/zone/{ZoneController,ZoneResponse,CreateZoneRequest,UpdateZoneRequest}.java`
- `src/main/java/com/usal/whbackend/api/warehouse/line/{LineController,LineResponse,CreateLineRequest,UpdateLineRequest}.java`
- `src/main/java/com/usal/whbackend/api/warehouse/position/{PositionController,PositionResponse,PositionDetailResponse,CreatePositionRequest,UpdatePositionRequest}.java`
- `src/test/java/com/usal/whbackend/service/{ZoneServiceTest,LineServiceTest,PositionServiceTest}.java`
- `src/test/java/com/usal/whbackend/api/warehouse/zone/ZoneControllerTest.java`
- `src/test/java/com/usal/whbackend/api/warehouse/line/LineControllerTest.java`
- `src/test/java/com/usal/whbackend/api/warehouse/position/PositionControllerTest.java`

**Modify:**
- `docker-compose.yml` — add `--replSet rs0` to MongoDB
- `src/main/java/com/usal/whbackend/config/MongoConfig.java` — add `MongoTransactionManager`
- `src/main/java/com/usal/whbackend/config/DataInitializer.java` — seed warehouse structure
- `src/main/java/com/usal/whbackend/api/error/GlobalExceptionHandler.java` — new exception handlers
- `src/main/java/com/usal/whbackend/domain/Product.java` — remove stock/location fields
- `src/main/java/com/usal/whbackend/repository/ProductRepository.java` — remove `updateStock`
- `src/main/java/com/usal/whbackend/service/ProductService.java` — computed stock, location, cascade delete
- `src/main/java/com/usal/whbackend/service/StockEventPublisher.java` — updated signature
- `src/main/java/com/usal/whbackend/api/product/ProductController.java` — add `/location` endpoint
- `src/main/java/com/usal/whbackend/api/product/ProductResponse.java` — remove Location, computed stock
- `src/main/java/com/usal/whbackend/api/product/CreateProductRequest.java` — remove location/stock fields
- `src/main/java/com/usal/whbackend/api/product/UpdateProductRequest.java` — remove location/stock fields
- `src/main/java/com/usal/whbackend/service/OrderService.java` — computed stock check, remove updateStock calls
- `src/main/java/com/usal/whbackend/repository/kafka/OrderStatusConsumer.java` — FIFO drain on completion
- `src/test/java/com/usal/whbackend/service/OrderServiceTest.java` — update for new stock logic
- `src/test/java/com/usal/whbackend/service/ProductServiceTest.java` — update for computed stock
- `src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java` — update for removed fields
- `src/test/java/com/usal/whbackend/domain/ProductTest.java` — update for removed fields

---

## Task 1: MongoDB Replica Set + Transaction Manager

**Files:**
- Modify: `docker-compose.yml`
- Modify: `src/main/java/com/usal/whbackend/config/MongoConfig.java`

- [ ] **Step 1: Enable replica set in docker-compose.yml**

Replace the `mongodb:` service block with:
```yaml
  mongodb:
    image: mongo:7
    ports:
      - "27017:27017"
    volumes:
      - ./data/mongodb:/data/db
    networks:
      - wh-network
    command: ["--replSet", "rs0", "--bind_ip_all"]
    healthcheck:
      test: |
        mongosh --eval "
          try { rs.status().ok } catch(e) { rs.initiate({_id:'rs0',members:[{_id:0,host:'localhost:27017'}]}) }
        " --quiet
      interval: 10s
      timeout: 10s
      retries: 10
      start_period: 30s
```

- [ ] **Step 2: Add MongoTransactionManager to MongoConfig.java**

```java
package com.usal.whbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@EnableMongoAuditing
public class MongoConfig {

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
    return new MongoTransactionManager(dbFactory);
  }
}
```

- [ ] **Step 3: Restart Docker and verify**

```bash
docker compose down -v && docker compose up -d
# Wait ~30s, then:
docker compose logs mongodb | grep -E "transition|PRIMARY|initiate"
```
Expected: lines mentioning `PRIMARY` or `transition to primary`.

- [ ] **Step 4: Verify app starts**

```bash
./gradlew bootRun
```
Expected: `Started WhBackendApplication` with no `MongoCommandException` in logs.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml src/main/java/com/usal/whbackend/config/MongoConfig.java
git commit -m "feat: enable mongodb replica set and transaction manager"
```

---

## Task 2: Global Jackson Config + Remove @JsonProperty + Add @Valid

**Files:**
- Create: `src/main/java/com/usal/whbackend/config/JacksonConfig.java`
- Modify: all existing request/response records

- [ ] **Step 1: Create JacksonConfig.java**

```java
package com.usal.whbackend.config;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
    return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
  }
}
```

- [ ] **Step 2: Strip @JsonProperty from CreateProductRequest.java**

```java
package com.usal.whbackend.api.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request body for creating a new product")
public record CreateProductRequest(
    @NotBlank @Schema(description = "Unique product identifier", example = "SKU-001") String sku,
    @NotBlank @Schema(description = "Product display name", example = "Casco de seguridad")
        String name,
    @Schema(description = "Product description") String description,
    @NotBlank @Schema(description = "Product category", example = "seguridad") String category,
    @Schema(description = "Full URL of the product image") String imageUrl,
    @Min(0) @Schema(description = "Maximum units a single order can request") Integer maxQuantityPerOrder,
    @Min(0) @Schema(description = "Minimum stock threshold for restock alert") Integer minimumStock) {}
```

Note: `availableStock`, `zone`, `line`, `position`, `height` are removed entirely.

- [ ] **Step 3: Strip @JsonProperty from UpdateProductRequest.java**

```java
package com.usal.whbackend.api.product;

import jakarta.validation.constraints.Min;

public record UpdateProductRequest(
    String name,
    String description,
    String category,
    String imageUrl,
    @Min(0) Integer maxQuantityPerOrder,
    @Min(0) Integer minimumStock,
    Boolean isActive) {}
```

Note: `availableStock`, `zone`, `line`, `position`, `height` removed. `minimumStock` kept (stored field). `isActive` added so admins can reactivate products.

- [ ] **Step 4: Strip @JsonProperty from LoginRequest.java**

Open `src/main/java/com/usal/whbackend/api/auth/LoginRequest.java`. Remove all `@JsonProperty` annotations — the global config handles snake_case automatically.

- [ ] **Step 5: Add @Valid to ProductController**

In `ProductController.java`, add `@Valid` to the `createProduct` and `updateProduct` method parameters:
```java
public ResponseEntity<Map<String, ProductResponse>> createProduct(
    @Valid @RequestBody CreateProductRequest request) { ... }

public ResponseEntity<Map<String, ProductResponse>> updateProduct(
    @PathVariable String id, @Valid @RequestBody UpdateProductRequest request) { ... }
```
Add import: `import jakarta.validation.Valid;`

- [ ] **Step 6: Run tests**

```bash
./gradlew test
```
Expected: all existing tests pass. If any snake_case field assertions fail, the global config is working and the test expectations need updating to match (e.g. `$.image_url` now works without `@JsonProperty`).

- [ ] **Step 7: Apply formatter**

```bash
./gradlew spotlessApply
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: global jackson snake_case config, add bean validation to product endpoints"
```

---

## Task 3: StockSize enum + Zone domain + ZoneRepository + ZoneService

**Files:**
- Create: `src/main/java/com/usal/whbackend/domain/StockSize.java`
- Create: `src/main/java/com/usal/whbackend/domain/Zone.java`
- Create: `src/main/java/com/usal/whbackend/repository/ZoneRepository.java`
- Create: `src/main/java/com/usal/whbackend/service/exception/ZoneNotFoundException.java`
- Create: `src/main/java/com/usal/whbackend/service/exception/ZoneCodeAlreadyExistsException.java`
- Create: `src/main/java/com/usal/whbackend/service/ZoneService.java`
- Create: `src/test/java/com/usal/whbackend/service/ZoneServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.zone.CreateZoneRequest;
import com.usal.whbackend.api.warehouse.zone.UpdateZoneRequest;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZoneServiceTest {

  @Mock ZoneRepository zoneRepository;
  @InjectMocks ZoneService zoneService;

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(false);
    z.setMaxAllowedLines(10);
    return z;
  }

  @Test
  void getZones_returnsAll() {
    when(zoneRepository.findAll()).thenReturn(List.of(zone("z1", "A")));
    assertEquals(1, zoneService.getZones().size());
  }

  @Test
  void createZone_duplicateCode_throwsZoneCodeAlreadyExists() {
    when(zoneRepository.findByZoneCode("A")).thenReturn(Optional.of(zone("z1", "A")));
    CreateZoneRequest req = new CreateZoneRequest("A", 10);
    assertThrows(ZoneCodeAlreadyExistsException.class, () -> zoneService.createZone(req));
  }

  @Test
  void createZone_newCode_savesAndReturns() {
    when(zoneRepository.findByZoneCode("B")).thenReturn(Optional.empty());
    Zone saved = zone("z2", "B");
    when(zoneRepository.save(any())).thenReturn(saved);
    Zone result = zoneService.createZone(new CreateZoneRequest("B", 5));
    assertEquals("B", result.getZoneCode());
    assertFalse(result.isActive());
  }

  @Test
  void updateZone_notFound_throwsZoneNotFound() {
    when(zoneRepository.findById("missing")).thenReturn(Optional.empty());
    assertThrows(ZoneNotFoundException.class,
        () -> zoneService.updateZone("missing", new UpdateZoneRequest(null, null, null)));
  }

  @Test
  void deleteZone_softDeletes() {
    Zone z = zone("z1", "A");
    z.setActive(true);
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(z));
    when(zoneRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    zoneService.deleteZone("z1");
    verify(zoneRepository).save(argThat(saved -> !((Zone) saved).isActive()));
  }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
./gradlew test --tests "com.usal.whbackend.service.ZoneServiceTest" 2>&1 | tail -20
```
Expected: compile errors (classes don't exist yet).

- [ ] **Step 3: Create StockSize.java**

```java
package com.usal.whbackend.domain;

public enum StockSize {
  PEQUENO,
  MEDIANO,
  GRANDE
}
```

- [ ] **Step 4: Create Zone.java**

```java
package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "zones")
public class Zone {

  @Id private String id;

  @Indexed(unique = true)
  private String zoneCode;

  private boolean isActive;
  private int maxAllowedLines;
  private Instant createdAt;

  public Zone() {}

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getZoneCode() { return zoneCode; }
  public void setZoneCode(String zoneCode) { this.zoneCode = zoneCode; }
  public boolean isActive() { return isActive; }
  public void setActive(boolean active) { isActive = active; }
  public int getMaxAllowedLines() { return maxAllowedLines; }
  public void setMaxAllowedLines(int maxAllowedLines) { this.maxAllowedLines = maxAllowedLines; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 5: Create ZoneRepository.java**

```java
package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Zone;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ZoneRepository extends MongoRepository<Zone, String> {
  Optional<Zone> findByZoneCode(String zoneCode);
}
```

- [ ] **Step 6: Create ZoneNotFoundException.java and ZoneCodeAlreadyExistsException.java**

```java
package com.usal.whbackend.service.exception;

public class ZoneNotFoundException extends RuntimeException {
  public ZoneNotFoundException(String id) {
    super("Zone not found: " + id);
  }
}
```

```java
package com.usal.whbackend.service.exception;

public class ZoneCodeAlreadyExistsException extends RuntimeException {
  public ZoneCodeAlreadyExistsException(String code) {
    super("Zone code already exists: " + code);
  }
}
```

- [ ] **Step 7: Create stub DTOs needed by the test**

`src/main/java/com/usal/whbackend/api/warehouse/zone/CreateZoneRequest.java`:
```java
package com.usal.whbackend.api.warehouse.zone;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateZoneRequest(
    @NotBlank String zoneCode,
    @Min(1) int maxAllowedLines) {}
```

`src/main/java/com/usal/whbackend/api/warehouse/zone/UpdateZoneRequest.java`:
```java
package com.usal.whbackend.api.warehouse.zone;

import jakarta.validation.constraints.Min;

public record UpdateZoneRequest(
    String zoneCode,
    @Min(1) Integer maxAllowedLines,
    Boolean isActive) {}
```

- [ ] **Step 8: Create ZoneService.java**

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.zone.CreateZoneRequest;
import com.usal.whbackend.api.warehouse.zone.UpdateZoneRequest;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ZoneService {

  private final ZoneRepository zoneRepository;

  public ZoneService(ZoneRepository zoneRepository) {
    this.zoneRepository = zoneRepository;
  }

  public List<Zone> getZones() {
    return zoneRepository.findAll();
  }

  public Zone getZone(String id) {
    return zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
  }

  public Zone createZone(CreateZoneRequest request) {
    if (zoneRepository.findByZoneCode(request.zoneCode()).isPresent()) {
      throw new ZoneCodeAlreadyExistsException(request.zoneCode());
    }
    Zone zone = new Zone();
    zone.setZoneCode(request.zoneCode());
    zone.setMaxAllowedLines(request.maxAllowedLines());
    zone.setActive(false);
    zone.setCreatedAt(Instant.now());
    return zoneRepository.save(zone);
  }

  public Zone updateZone(String id, UpdateZoneRequest request) {
    Zone zone = zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
    if (request.zoneCode() != null) zone.setZoneCode(request.zoneCode());
    if (request.maxAllowedLines() != null) zone.setMaxAllowedLines(request.maxAllowedLines());
    if (request.isActive() != null) zone.setActive(request.isActive());
    return zoneRepository.save(zone);
  }

  public void deleteZone(String id) {
    Zone zone = zoneRepository.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
    zone.setActive(false);
    zoneRepository.save(zone);
  }
}
```

- [ ] **Step 9: Run tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.ZoneServiceTest"
```
Expected: 5 tests pass.

- [ ] **Step 10: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: zone domain, repository, and service"
```

---

## Task 4: ZoneController + ZoneResponse + GlobalExceptionHandler

**Files:**
- Create: `src/main/java/com/usal/whbackend/api/warehouse/zone/ZoneController.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/zone/ZoneResponse.java`
- Modify: `src/main/java/com/usal/whbackend/api/error/GlobalExceptionHandler.java`
- Create: `src/test/java/com/usal/whbackend/api/warehouse/zone/ZoneControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

```java
package com.usal.whbackend.api.warehouse.zone;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.service.ZoneService;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ZoneController.class)
class ZoneControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean ZoneService zoneService;
  @MockitoBean JwtService jwtService;

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    z.setActive(false);
    z.setMaxAllowedLines(10);
    return z;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getZones_returns200WithList() throws Exception {
    when(zoneService.getZones()).thenReturn(List.of(zone("z1", "A")));
    mockMvc.perform(get("/warehouse/zones"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.zones").isArray())
        .andExpect(jsonPath("$.zones[0].zone_code").value("A"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createZone_valid_returns201() throws Exception {
    when(zoneService.createZone(any())).thenReturn(zone("z1", "A"));
    mockMvc.perform(post("/warehouse/zones")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"zone_code\":\"A\",\"max_allowed_lines\":10}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.zone_code").value("A"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createZone_duplicateCode_returns409() throws Exception {
    when(zoneService.createZone(any())).thenThrow(new ZoneCodeAlreadyExistsException("A"));
    mockMvc.perform(post("/warehouse/zones")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"zone_code\":\"A\",\"max_allowed_lines\":10}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ZONE_CODE_ALREADY_EXISTS"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteZone_notFound_returns404() throws Exception {
    doThrow(new ZoneNotFoundException("z99")).when(zoneService).deleteZone("z99");
    mockMvc.perform(delete("/warehouse/zones/z99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("ZONE_NOT_FOUND"));
  }

  @Test
  void getZones_unauthenticated_returns401() throws Exception {
    mockMvc.perform(get("/warehouse/zones")).andExpect(status().isUnauthorized());
  }
}
```

- [ ] **Step 2: Create ZoneResponse.java**

```java
package com.usal.whbackend.api.warehouse.zone;

import com.usal.whbackend.domain.Zone;
import java.time.Instant;

public record ZoneResponse(
    String idZone,
    String zoneCode,
    boolean isActive,
    int maxAllowedLines,
    Instant createdAt) {

  public static ZoneResponse from(Zone zone) {
    return new ZoneResponse(
        zone.getId(),
        zone.getZoneCode(),
        zone.isActive(),
        zone.getMaxAllowedLines(),
        zone.getCreatedAt());
  }
}
```

- [ ] **Step 3: Create ZoneController.java**

```java
package com.usal.whbackend.api.warehouse.zone;

import com.usal.whbackend.service.ZoneService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/warehouse/zones")
@Tag(name = "Warehouse - Zones")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class ZoneController {

  private final ZoneService zoneService;

  public ZoneController(ZoneService zoneService) {
    this.zoneService = zoneService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> getZones() {
    return ResponseEntity.ok(
        Map.of("zones", zoneService.getZones().stream().map(ZoneResponse::from).toList()));
  }

  @PostMapping
  public ResponseEntity<ZoneResponse> createZone(@Valid @RequestBody CreateZoneRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ZoneResponse.from(zoneService.createZone(request)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ZoneResponse> updateZone(
      @PathVariable String id, @Valid @RequestBody UpdateZoneRequest request) {
    return ResponseEntity.ok(ZoneResponse.from(zoneService.updateZone(id, request)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteZone(@PathVariable String id) {
    zoneService.deleteZone(id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 4: Add exception handlers to GlobalExceptionHandler.java**

Add these imports and handlers to the existing `GlobalExceptionHandler`:

```java
// New imports to add:
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import com.usal.whbackend.service.exception.ZoneCodeAlreadyExistsException;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;

// New handlers to add inside the class:
  @ExceptionHandler(ZoneNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleZoneNotFound(ZoneNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("ZONE_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(ZoneCodeAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleZoneCodeExists(ZoneCodeAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("ZONE_CODE_ALREADY_EXISTS", ex.getMessage()));
  }

  @ExceptionHandler(LineNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleLineNotFound(LineNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("LINE_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(LineNumberAlreadyExistsException.class)
  public ResponseEntity<ErrorResponse> handleLineNumberExists(LineNumberAlreadyExistsException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("LINE_NUMBER_ALREADY_EXISTS", ex.getMessage()));
  }

  @ExceptionHandler(PositionNotFoundException.class)
  public ResponseEntity<ErrorResponse> handlePositionNotFound(PositionNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ErrorResponse.of("POSITION_NOT_FOUND", ex.getMessage()));
  }

  @ExceptionHandler(PositionAlreadyOccupiedException.class)
  public ResponseEntity<ErrorResponse> handlePositionOccupied(PositionAlreadyOccupiedException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(ErrorResponse.of("POSITION_ALREADY_OCCUPIED", ex.getMessage()));
  }

  @ExceptionHandler(StockExceedsCapacityException.class)
  public ResponseEntity<ErrorResponse> handleStockExceedsCapacity(StockExceedsCapacityException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(ErrorResponse.of("STOCK_EXCEEDS_CAPACITY", ex.getMessage()));
  }
```

(Create stub exception classes for Line/Position now so the file compiles — fill implementations in Tasks 5–7.)

- [ ] **Step 5: Create stub exceptions for Line and Position**

```java
// LineNotFoundException.java
package com.usal.whbackend.service.exception;
public class LineNotFoundException extends RuntimeException {
  public LineNotFoundException(String id) { super("Line not found: " + id); }
}

// LineNumberAlreadyExistsException.java
package com.usal.whbackend.service.exception;
public class LineNumberAlreadyExistsException extends RuntimeException {
  public LineNumberAlreadyExistsException(int number, String zoneId) {
    super("Line number " + number + " already exists in zone " + zoneId);
  }
}

// PositionNotFoundException.java
package com.usal.whbackend.service.exception;
public class PositionNotFoundException extends RuntimeException {
  public PositionNotFoundException(String id) { super("Position not found: " + id); }
}

// PositionAlreadyOccupiedException.java
package com.usal.whbackend.service.exception;
public class PositionAlreadyOccupiedException extends RuntimeException {
  public PositionAlreadyOccupiedException(String positionId) {
    super("Position already occupied: " + positionId);
  }
}

// StockExceedsCapacityException.java
package com.usal.whbackend.service.exception;
public class StockExceedsCapacityException extends RuntimeException {
  public StockExceedsCapacityException(int stock, int capacity) {
    super("Stock " + stock + " exceeds capacity " + capacity);
  }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew test --tests "com.usal.whbackend.api.warehouse.zone.ZoneControllerTest"
```
Expected: 5 tests pass.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: zone controller, response, and exception handlers"
```

---

## Task 5: Line domain + LineRepository + LineService

**Files:**
- Create: `src/main/java/com/usal/whbackend/domain/Line.java`
- Create: `src/main/java/com/usal/whbackend/repository/LineRepository.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/line/CreateLineRequest.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/line/UpdateLineRequest.java`
- Create: `src/main/java/com/usal/whbackend/service/LineService.java`
- Create: `src/test/java/com/usal/whbackend/service/LineServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.line.CreateLineRequest;
import com.usal.whbackend.api.warehouse.line.UpdateLineRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LineServiceTest {

  @Mock LineRepository lineRepository;
  @Mock ZoneRepository zoneRepository;
  @InjectMocks LineService lineService;

  private Zone zone(String id) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode("A");
    return z;
  }

  private Line line(String id, String zoneId, int number) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    l.setNumberLine(number);
    l.setActive(false);
    l.setMaxAllowedPositions(20);
    return l;
  }

  @Test
  void getLinesByZone_returnsLinesForZone() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZone("z1")).thenReturn(List.of(line("l1", "z1", 1)));
    assertEquals(1, lineService.getLinesByZone("z1").size());
  }

  @Test
  void getLinesByZone_zoneNotFound_throws() {
    when(zoneRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(ZoneNotFoundException.class, () -> lineService.getLinesByZone("bad"));
  }

  @Test
  void createLine_duplicateNumber_throwsLineNumberAlreadyExists() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 1)).thenReturn(Optional.of(line("l1","z1",1)));
    assertThrows(LineNumberAlreadyExistsException.class,
        () -> lineService.createLine("z1", new CreateLineRequest(1, 20)));
  }

  @Test
  void createLine_valid_savesWithZoneId() {
    when(zoneRepository.findById("z1")).thenReturn(Optional.of(zone("z1")));
    when(lineRepository.findByIdZoneAndNumberLine("z1", 2)).thenReturn(Optional.empty());
    Line saved = line("l2", "z1", 2);
    when(lineRepository.save(any())).thenReturn(saved);
    Line result = lineService.createLine("z1", new CreateLineRequest(2, 20));
    assertEquals("z1", result.getIdZone());
  }

  @Test
  void deleteLine_softDeletes() {
    Line l = line("l1", "z1", 1);
    l.setActive(true);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    when(lineRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    lineService.deleteLine("l1");
    verify(lineRepository).save(argThat(saved -> !((Line) saved).isActive()));
  }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
./gradlew test --tests "com.usal.whbackend.service.LineServiceTest" 2>&1 | tail -10
```

- [ ] **Step 3: Create Line.java**

```java
package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "lines")
@CompoundIndex(name = "zone_number_idx", def = "{'idZone': 1, 'numberLine': 1}", unique = true)
public class Line {

  @Id private String id;
  private String idZone;
  private int numberLine;
  private boolean isActive;
  private int maxAllowedPositions;
  private Instant createdAt;

  public Line() {}

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getIdZone() { return idZone; }
  public void setIdZone(String idZone) { this.idZone = idZone; }
  public int getNumberLine() { return numberLine; }
  public void setNumberLine(int numberLine) { this.numberLine = numberLine; }
  public boolean isActive() { return isActive; }
  public void setActive(boolean active) { isActive = active; }
  public int getMaxAllowedPositions() { return maxAllowedPositions; }
  public void setMaxAllowedPositions(int maxAllowedPositions) { this.maxAllowedPositions = maxAllowedPositions; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: Create LineRepository.java**

```java
package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Line;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LineRepository extends MongoRepository<Line, String> {
  List<Line> findByIdZone(String idZone);
  Optional<Line> findByIdZoneAndNumberLine(String idZone, int numberLine);
}
```

- [ ] **Step 5: Create CreateLineRequest.java and UpdateLineRequest.java**

```java
package com.usal.whbackend.api.warehouse.line;

import jakarta.validation.constraints.Min;

public record CreateLineRequest(
    @Min(1) int numberLine,
    @Min(1) int maxAllowedPositions) {}
```

```java
package com.usal.whbackend.api.warehouse.line;

import jakarta.validation.constraints.Min;

public record UpdateLineRequest(
    @Min(1) Integer numberLine,
    @Min(1) Integer maxAllowedPositions,
    Boolean isActive) {}
```

- [ ] **Step 6: Create LineService.java**

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.line.CreateLineRequest;
import com.usal.whbackend.api.warehouse.line.UpdateLineRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.LineNumberAlreadyExistsException;
import com.usal.whbackend.service.exception.ZoneNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LineService {

  private final LineRepository lineRepository;
  private final ZoneRepository zoneRepository;

  public LineService(LineRepository lineRepository, ZoneRepository zoneRepository) {
    this.lineRepository = lineRepository;
    this.zoneRepository = zoneRepository;
  }

  public List<Line> getLinesByZone(String zoneId) {
    zoneRepository.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
    return lineRepository.findByIdZone(zoneId);
  }

  public Line getLine(String id) {
    return lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
  }

  public Line createLine(String zoneId, CreateLineRequest request) {
    zoneRepository.findById(zoneId).orElseThrow(() -> new ZoneNotFoundException(zoneId));
    if (lineRepository.findByIdZoneAndNumberLine(zoneId, request.numberLine()).isPresent()) {
      throw new LineNumberAlreadyExistsException(request.numberLine(), zoneId);
    }
    Line line = new Line();
    line.setIdZone(zoneId);
    line.setNumberLine(request.numberLine());
    line.setMaxAllowedPositions(request.maxAllowedPositions());
    line.setActive(false);
    line.setCreatedAt(Instant.now());
    return lineRepository.save(line);
  }

  public Line updateLine(String id, UpdateLineRequest request) {
    Line line = lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
    if (request.numberLine() != null) line.setNumberLine(request.numberLine());
    if (request.maxAllowedPositions() != null) line.setMaxAllowedPositions(request.maxAllowedPositions());
    if (request.isActive() != null) line.setActive(request.isActive());
    return lineRepository.save(line);
  }

  public void deleteLine(String id) {
    Line line = lineRepository.findById(id).orElseThrow(() -> new LineNotFoundException(id));
    line.setActive(false);
    lineRepository.save(line);
  }
}
```

- [ ] **Step 7: Run tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.LineServiceTest"
```
Expected: 5 tests pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: line domain, repository, and service"
```

---

## Task 6: LineController + LineResponse

**Files:**
- Create: `src/main/java/com/usal/whbackend/api/warehouse/line/LineController.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/line/LineResponse.java`
- Create: `src/test/java/com/usal/whbackend/api/warehouse/line/LineControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

```java
package com.usal.whbackend.api.warehouse.line;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.service.LineService;
import com.usal.whbackend.service.exception.LineNotFoundException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LineController.class)
class LineControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean LineService lineService;
  @MockitoBean JwtService jwtService;

  private Line line(String id, String zoneId) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    l.setNumberLine(1);
    l.setActive(false);
    l.setMaxAllowedPositions(20);
    return l;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getLines_returns200() throws Exception {
    when(lineService.getLinesByZone("z1")).thenReturn(List.of(line("l1", "z1")));
    mockMvc.perform(get("/warehouse/zones/z1/lines"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lines").isArray())
        .andExpect(jsonPath("$.lines[0].id_line").value("l1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createLine_valid_returns201() throws Exception {
    when(lineService.createLine(eq("z1"), any())).thenReturn(line("l1", "z1"));
    mockMvc.perform(post("/warehouse/zones/z1/lines")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"number_line\":1,\"max_allowed_positions\":20}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id_line").value("l1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteLine_notFound_returns404() throws Exception {
    doThrow(new LineNotFoundException("l99")).when(lineService).deleteLine("l99");
    mockMvc.perform(delete("/warehouse/lines/l99"))
        .andExpect(status().isNotFound());
  }
}
```

- [ ] **Step 2: Create LineResponse.java**

```java
package com.usal.whbackend.api.warehouse.line;

import com.usal.whbackend.domain.Line;
import java.time.Instant;

public record LineResponse(
    String idLine,
    String idZone,
    int numberLine,
    boolean isActive,
    int maxAllowedPositions,
    Instant createdAt) {

  public static LineResponse from(Line line) {
    return new LineResponse(
        line.getId(),
        line.getIdZone(),
        line.getNumberLine(),
        line.isActive(),
        line.getMaxAllowedPositions(),
        line.getCreatedAt());
  }
}
```

- [ ] **Step 3: Create LineController.java**

```java
package com.usal.whbackend.api.warehouse.line;

import com.usal.whbackend.service.LineService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Warehouse - Lines")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class LineController {

  private final LineService lineService;

  public LineController(LineService lineService) {
    this.lineService = lineService;
  }

  @GetMapping("/warehouse/zones/{zoneId}/lines")
  public ResponseEntity<Map<String, Object>> getLines(@PathVariable String zoneId) {
    return ResponseEntity.ok(
        Map.of("lines", lineService.getLinesByZone(zoneId).stream().map(LineResponse::from).toList()));
  }

  @PostMapping("/warehouse/zones/{zoneId}/lines")
  public ResponseEntity<LineResponse> createLine(
      @PathVariable String zoneId, @Valid @RequestBody CreateLineRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(LineResponse.from(lineService.createLine(zoneId, request)));
  }

  @PatchMapping("/warehouse/lines/{id}")
  public ResponseEntity<LineResponse> updateLine(
      @PathVariable String id, @Valid @RequestBody UpdateLineRequest request) {
    return ResponseEntity.ok(LineResponse.from(lineService.updateLine(id, request)));
  }

  @DeleteMapping("/warehouse/lines/{id}")
  public ResponseEntity<Void> deleteLine(@PathVariable String id) {
    lineService.deleteLine(id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "com.usal.whbackend.api.warehouse.line.LineControllerTest"
```
Expected: 3 tests pass.

- [ ] **Step 5: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: line controller and response"
```

---

## Task 7: Position domain + PositionRepository + PositionService

**Files:**
- Create: `src/main/java/com/usal/whbackend/domain/Position.java`
- Create: `src/main/java/com/usal/whbackend/repository/PositionRepository.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/position/CreatePositionRequest.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/position/UpdatePositionRequest.java`
- Create: `src/main/java/com/usal/whbackend/service/PositionService.java`
- Create: `src/test/java/com/usal/whbackend/service/PositionServiceTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock PositionRepository positionRepository;
  @Mock LineRepository lineRepository;
  @InjectMocks PositionService positionService;

  private Line line(String id, String zoneId) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    return l;
  }

  private Position position(String id, String lineId, String zoneId) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(lineId);
    p.setIdZone(zoneId);
    p.setPositionName("P01");
    p.setMaximumCapacity(100);
    p.setCurrentStock(0);
    p.setSizeStockToSave(StockSize.MEDIANO);
    p.setActive(false);
    return p;
  }

  @Test
  void getPositionsByLine_lineNotFound_throws() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(LineNotFoundException.class, () -> positionService.getPositionsByLine("bad"));
  }

  @Test
  void getPositionsByLine_returnsPositions() {
    when(lineRepository.findById("l1")).thenReturn(Optional.of(line("l1", "z1")));
    when(positionRepository.findByIdLine("l1")).thenReturn(List.of(position("p1", "l1", "z1")));
    assertEquals(1, positionService.getPositionsByLine("l1").size());
  }

  @Test
  void createPosition_valid_inheritsZoneFromLine() {
    when(lineRepository.findById("l1")).thenReturn(Optional.of(line("l1", "z1")));
    Position saved = position("p1", "l1", "z1");
    when(positionRepository.save(any())).thenReturn(saved);
    Position result = positionService.createPosition("l1",
        new CreatePositionRequest("P01", 100, StockSize.MEDIANO));
    assertEquals("z1", result.getIdZone());
  }

  @Test
  void updatePosition_assignDifferentProduct_throwsPositionAlreadyOccupied() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, null, null, "product-B", null);
    assertThrows(PositionAlreadyOccupiedException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_stockExceedsCapacity_throws() {
    Position p = position("p1", "l1", "z1");
    p.setMaximumCapacity(50);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, 60, null, null, null);
    assertThrows(StockExceedsCapacityException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_unassignProduct_clearsStockAndProductId() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    p.setCurrentStock(50);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, null, null, null, true);
    Position result = positionService.updatePosition("p1", req);
    assertNull(result.getProductId());
    assertEquals(0, result.getCurrentStock());
  }

  @Test
  void deletePosition_softDeletes() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    positionService.deletePosition("p1");
    verify(positionRepository).save(argThat(saved -> !((Position) saved).isActive()));
  }
}
```

- [ ] **Step 2: Run tests — expect compile failure**

```bash
./gradlew test --tests "com.usal.whbackend.service.PositionServiceTest" 2>&1 | tail -10
```

- [ ] **Step 3: Create Position.java**

```java
package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "positions")
@CompoundIndex(name = "line_name_idx", def = "{'idLine': 1, 'positionName': 1}", unique = true)
public class Position {

  @Id private String id;
  private String idLine;
  private String idZone;
  private String positionName;
  private boolean isActive;
  private int maximumCapacity;
  private StockSize sizeStockToSave;

  @Indexed
  private String productId; // nullable — source of truth for assignment

  private int currentStock;
  private Instant createdAt;

  public Position() {}

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getIdLine() { return idLine; }
  public void setIdLine(String idLine) { this.idLine = idLine; }
  public String getIdZone() { return idZone; }
  public void setIdZone(String idZone) { this.idZone = idZone; }
  public String getPositionName() { return positionName; }
  public void setPositionName(String positionName) { this.positionName = positionName; }
  public boolean isActive() { return isActive; }
  public void setActive(boolean active) { isActive = active; }
  public int getMaximumCapacity() { return maximumCapacity; }
  public void setMaximumCapacity(int maximumCapacity) { this.maximumCapacity = maximumCapacity; }
  public StockSize getSizeStockToSave() { return sizeStockToSave; }
  public void setSizeStockToSave(StockSize sizeStockToSave) { this.sizeStockToSave = sizeStockToSave; }
  public String getProductId() { return productId; }
  public void setProductId(String productId) { this.productId = productId; }
  public int getCurrentStock() { return currentStock; }
  public void setCurrentStock(int currentStock) { this.currentStock = currentStock; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 4: Create PositionRepository.java**

```java
package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Position;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PositionRepository extends MongoRepository<Position, String> {
  List<Position> findByIdLine(String idLine);
  Optional<Position> findByProductId(String productId);
  List<Position> findByProductIdAndCurrentStockGreaterThanOrderByCreatedAtAsc(
      String productId, int minStock);
  List<Position> findByProductIdIn(List<String> productIds);
}
```

- [ ] **Step 5: Create CreatePositionRequest.java and UpdatePositionRequest.java**

```java
package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePositionRequest(
    @NotBlank String positionName,
    @Min(1) int maximumCapacity,
    @NotNull StockSize sizeStockToSave) {}
```

```java
package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;

public record UpdatePositionRequest(
    String positionName,
    Boolean isActive,
    @Min(0) Integer currentStock,
    StockSize sizeStockToSave,
    String productId,         // new product to assign (or keep current if null)
    Boolean unassignProduct)  // if true, clears productId and currentStock
    {}
```

- [ ] **Step 6: Create PositionService.java**

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PositionService {

  private final PositionRepository positionRepository;
  private final LineRepository lineRepository;

  public PositionService(PositionRepository positionRepository, LineRepository lineRepository) {
    this.positionRepository = positionRepository;
    this.lineRepository = lineRepository;
  }

  public List<Position> getPositionsByLine(String lineId) {
    lineRepository.findById(lineId).orElseThrow(() -> new LineNotFoundException(lineId));
    return positionRepository.findByIdLine(lineId);
  }

  public Position getPosition(String id) {
    return positionRepository.findById(id).orElseThrow(() -> new PositionNotFoundException(id));
  }

  public Position createPosition(String lineId, CreatePositionRequest request) {
    var line = lineRepository.findById(lineId).orElseThrow(() -> new LineNotFoundException(lineId));
    Position position = new Position();
    position.setIdLine(lineId);
    position.setIdZone(line.getIdZone());
    position.setPositionName(request.positionName());
    position.setMaximumCapacity(request.maximumCapacity());
    position.setSizeStockToSave(request.sizeStockToSave());
    position.setActive(false);
    position.setCurrentStock(0);
    position.setCreatedAt(Instant.now());
    return positionRepository.save(position);
  }

  public Position updatePosition(String id, UpdatePositionRequest request) {
    Position position = positionRepository.findById(id)
        .orElseThrow(() -> new PositionNotFoundException(id));

    // Unassign takes priority
    if (Boolean.TRUE.equals(request.unassignProduct())) {
      position.setProductId(null);
      position.setCurrentStock(0);
    } else {
      // Guard: cannot assign a different product without unassigning first
      if (request.productId() != null
          && position.getProductId() != null
          && !position.getProductId().equals(request.productId())) {
        throw new PositionAlreadyOccupiedException(id);
      }
      if (request.productId() != null) {
        position.setProductId(request.productId());
      }

      // Guard: stock cannot exceed capacity
      int newStock = request.currentStock() != null ? request.currentStock() : position.getCurrentStock();
      if (newStock > position.getMaximumCapacity()) {
        throw new StockExceedsCapacityException(newStock, position.getMaximumCapacity());
      }
      if (newStock < 0) {
        throw new StockExceedsCapacityException(newStock, position.getMaximumCapacity());
      }
      if (request.currentStock() != null) position.setCurrentStock(newStock);
    }

    if (request.positionName() != null) position.setPositionName(request.positionName());
    if (request.isActive() != null) position.setActive(request.isActive());
    if (request.sizeStockToSave() != null) position.setSizeStockToSave(request.sizeStockToSave());

    return positionRepository.save(position);
  }

  public void deletePosition(String id) {
    Position position = positionRepository.findById(id)
        .orElseThrow(() -> new PositionNotFoundException(id));
    position.setActive(false);
    positionRepository.save(position);
  }
}
```

- [ ] **Step 7: Run tests — expect pass**

```bash
./gradlew test --tests "com.usal.whbackend.service.PositionServiceTest"
```
Expected: 6 tests pass.

- [ ] **Step 8: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: position domain, repository, and service"
```

---

## Task 8: PositionController + Responses

**Files:**
- Create: `src/main/java/com/usal/whbackend/api/warehouse/position/PositionController.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/position/PositionResponse.java`
- Create: `src/main/java/com/usal/whbackend/api/warehouse/position/PositionDetailResponse.java`
- Create: `src/test/java/com/usal/whbackend/api/warehouse/position/PositionControllerTest.java`

- [ ] **Step 1: Write the failing controller tests**

```java
package com.usal.whbackend.api.warehouse.position;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.service.PositionService;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PositionController.class)
class PositionControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean PositionService positionService;
  @MockitoBean JwtService jwtService;

  private Position position(String id, String lineId) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(lineId);
    p.setIdZone("z1");
    p.setPositionName("P01");
    p.setMaximumCapacity(100);
    p.setCurrentStock(50);
    p.setSizeStockToSave(StockSize.MEDIANO);
    return p;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getPositions_returns200() throws Exception {
    when(positionService.getPositionsByLine("l1")).thenReturn(List.of(position("p1", "l1")));
    mockMvc.perform(get("/warehouse/lines/l1/positions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.positions").isArray())
        .andExpect(jsonPath("$.positions[0].id_position").value("p1"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createPosition_valid_returns201() throws Exception {
    when(positionService.createPosition(eq("l1"), any())).thenReturn(position("p1", "l1"));
    mockMvc.perform(post("/warehouse/lines/l1/positions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"position_name\":\"P01\",\"maximum_capacity\":100,\"size_stock_to_save\":\"MEDIANO\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updatePosition_occupied_returns409() throws Exception {
    when(positionService.updatePosition(eq("p1"), any()))
        .thenThrow(new PositionAlreadyOccupiedException("p1"));
    mockMvc.perform(patch("/warehouse/positions/p1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"product_id\":\"other-product\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("POSITION_ALREADY_OCCUPIED"));
  }
}
```

- [ ] **Step 2: Create PositionResponse.java**

```java
package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;

public record PositionResponse(
    String idPosition,
    String idLine,
    String idZone,
    String positionName,
    boolean isActive,
    int maximumCapacity,
    StockSize sizeStockToSave,
    String productId,
    int currentStock,
    Instant createdAt) {

  public static PositionResponse from(Position p) {
    return new PositionResponse(
        p.getId(), p.getIdLine(), p.getIdZone(), p.getPositionName(),
        p.isActive(), p.getMaximumCapacity(), p.getSizeStockToSave(),
        p.getProductId(), p.getCurrentStock(), p.getCreatedAt());
  }
}
```

- [ ] **Step 3: Create PositionDetailResponse.java**

```java
package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;

public record PositionDetailResponse(
    String idPosition,
    String idLine,
    String idZone,
    String positionName,
    boolean isActive,
    int maximumCapacity,
    StockSize sizeStockToSave,
    String productId,
    int currentStock,
    Instant createdAt,
    AssignedProduct assignedProduct) {

  public record AssignedProduct(String id, String sku, String name) {}

  public static PositionDetailResponse from(Position p, Product product) {
    AssignedProduct ap = product == null
        ? null
        : new AssignedProduct(product.getId(), product.getSku(), product.getName());
    return new PositionDetailResponse(
        p.getId(), p.getIdLine(), p.getIdZone(), p.getPositionName(),
        p.isActive(), p.getMaximumCapacity(), p.getSizeStockToSave(),
        p.getProductId(), p.getCurrentStock(), p.getCreatedAt(), ap);
  }
}
```

- [ ] **Step 4: Create PositionController.java**

```java
package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.PositionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Warehouse - Positions")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class PositionController {

  private final PositionService positionService;
  private final ProductRepository productRepository;

  public PositionController(PositionService positionService, ProductRepository productRepository) {
    this.positionService = positionService;
    this.productRepository = productRepository;
  }

  @GetMapping("/warehouse/lines/{lineId}/positions")
  public ResponseEntity<Map<String, Object>> getPositions(@PathVariable String lineId) {
    return ResponseEntity.ok(Map.of("positions",
        positionService.getPositionsByLine(lineId).stream().map(PositionResponse::from).toList()));
  }

  @GetMapping("/warehouse/positions/{id}")
  public ResponseEntity<PositionDetailResponse> getPosition(@PathVariable String id) {
    Position position = positionService.getPosition(id);
    Product product = position.getProductId() != null
        ? productRepository.findById(position.getProductId()).orElse(null)
        : null;
    return ResponseEntity.ok(PositionDetailResponse.from(position, product));
  }

  @PostMapping("/warehouse/lines/{lineId}/positions")
  public ResponseEntity<PositionResponse> createPosition(
      @PathVariable String lineId, @Valid @RequestBody CreatePositionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(PositionResponse.from(positionService.createPosition(lineId, request)));
  }

  @PatchMapping("/warehouse/positions/{id}")
  public ResponseEntity<PositionResponse> updatePosition(
      @PathVariable String id, @Valid @RequestBody UpdatePositionRequest request) {
    return ResponseEntity.ok(PositionResponse.from(positionService.updatePosition(id, request)));
  }

  @DeleteMapping("/warehouse/positions/{id}")
  public ResponseEntity<Void> deletePosition(@PathVariable String id) {
    positionService.deletePosition(id);
    return ResponseEntity.noContent().build();
  }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew test --tests "com.usal.whbackend.api.warehouse.position.PositionControllerTest"
```
Expected: 3 tests pass.

- [ ] **Step 6: Run full test suite to confirm no regressions**

```bash
./gradlew test
```
Expected: all tests pass.

- [ ] **Step 7: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: position controller and responses — warehouse module complete"
```

---

## Task 9: Product Refactor — Computed Stock + Location Endpoint

**Files:**
- Modify: `src/main/java/com/usal/whbackend/domain/Product.java`
- Modify: `src/main/java/com/usal/whbackend/repository/ProductRepository.java`
- Modify: `src/main/java/com/usal/whbackend/service/ProductService.java`
- Modify: `src/main/java/com/usal/whbackend/service/StockEventPublisher.java`
- Modify: `src/main/java/com/usal/whbackend/api/product/ProductController.java`
- Modify: `src/main/java/com/usal/whbackend/api/product/ProductResponse.java`
- Modify: `src/main/java/com/usal/whbackend/api/product/CreateProductRequest.java` (done in Task 2)
- Modify: `src/main/java/com/usal/whbackend/api/product/UpdateProductRequest.java` (done in Task 2)
- Modify: `src/test/java/com/usal/whbackend/service/ProductServiceTest.java`
- Modify: `src/test/java/com/usal/whbackend/api/product/ProductControllerTest.java`
- Modify: `src/test/java/com/usal/whbackend/domain/ProductTest.java`

- [ ] **Step 1: Remove stock and location fields from Product.java**

Replace the entire `Product.java` with:

```java
package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "products")
@CompoundIndex(name = "category_active_idx", def = "{'category': 1, 'active': 1}")
public class Product {

  @Id private String id;

  @Indexed(unique = true)
  private String sku;

  private String name;
  private String description;
  private String category;
  private String imageUrl;
  private int maxQuantityPerOrder;
  private int minimumStock;
  private boolean active;
  private Instant createdAt;

  public Product() {}

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getSku() { return sku; }
  public void setSku(String sku) { this.sku = sku; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }
  public String getImageUrl() { return imageUrl; }
  public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
  public int getMaxQuantityPerOrder() { return maxQuantityPerOrder; }
  public void setMaxQuantityPerOrder(int maxQuantityPerOrder) { this.maxQuantityPerOrder = maxQuantityPerOrder; }
  public int getMinimumStock() { return minimumStock; }
  public void setMinimumStock(int minimumStock) { this.minimumStock = minimumStock; }
  public boolean isActive() { return active; }
  public void setActive(boolean active) { this.active = active; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
```

- [ ] **Step 2: Remove updateStock from ProductRepository.java**

Replace with:
```java
package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRepository extends MongoRepository<Product, String> {
  Optional<Product> findBySku(String sku);
  List<Product> findByCategory(String category);
  List<Product> findByActive(boolean active);
  List<Product> findByCategoryAndActive(String category, boolean active);
}
```

- [ ] **Step 3: Update StockEventPublisher interface**

Read `src/main/java/com/usal/whbackend/service/StockEventPublisher.java` first, then change its method signature to:

```java
package com.usal.whbackend.service;

import com.usal.whbackend.domain.Product;

public interface StockEventPublisher {
  void broadcastStockAlert(Product product, int currentStock);
}
```

Then update the websocket implementation. Read `src/main/java/com/usal/whbackend/api/websocket/StockAlertWebSocketHandler.java` and update its `broadcastStockAlert` method to accept `int currentStock` as a second parameter and use it in the event payload instead of `product.getAvailableStock()`.

- [ ] **Step 4: Update ProductResponse.java**

```java
package com.usal.whbackend.api.product;

import com.usal.whbackend.domain.Product;
import java.time.Instant;

public record ProductResponse(
    String id,
    String sku,
    String name,
    String description,
    String category,
    String imageUrl,
    Stock stock,
    OrderConstraints orderConstraints,
    boolean active,
    Instant createdAt) {

  public record Stock(int available, int reserved, int min) {}

  public record OrderConstraints(int maxQuantityPerOrder) {}

  public static ProductResponse from(Product product, int availableStock, int reservedStock) {
    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getCategory(),
        product.getImageUrl(),
        new Stock(availableStock, reservedStock, product.getMinimumStock()),
        new OrderConstraints(product.getMaxQuantityPerOrder()),
        product.isActive(),
        product.getCreatedAt());
  }
}
```

- [ ] **Step 5: Replace ProductService.java**

```java
package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.ProductResponse;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.api.warehouse.position.PositionResponse;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final PositionRepository positionRepository;
  private final MongoTemplate mongoTemplate;

  public ProductService(
      ProductRepository productRepository,
      PositionRepository positionRepository,
      MongoTemplate mongoTemplate) {
    this.productRepository = productRepository;
    this.positionRepository = positionRepository;
    this.mongoTemplate = mongoTemplate;
  }

  // ── Stock computation ──────────────────────────────────────────────────────

  /** Sum of current_stock across all positions for a product. */
  public int computeAvailableStock(String productId) {
    return positionRepository.findByProductIdIn(List.of(productId)).stream()
        .mapToInt(Position::getCurrentStock)
        .sum();
  }

  /**
   * Sum of quantities in pending/in_progress orders for a product.
   * Uses MongoTemplate aggregation: unwind items, match product, sum quantities.
   */
  public int computeReservedStock(String productId) {
    var agg = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("status").in("PENDING", "IN_PROGRESS")),
        Aggregation.unwind("items"),
        Aggregation.match(Criteria.where("items.productId").is(productId)),
        Aggregation.group().sum("items.quantity").as("total")
    );
    AggregationResults<StockSum> results =
        mongoTemplate.aggregate(agg, "orders", StockSum.class);
    StockSum sum = results.getUniqueMappedResult();
    return sum != null ? sum.total() : 0;
  }

  /**
   * Bulk available stocks for a list of product IDs — one DB call for a whole page.
   */
  private Map<String, Integer> bulkAvailableStock(List<String> productIds) {
    return positionRepository.findByProductIdIn(productIds).stream()
        .collect(Collectors.groupingBy(
            Position::getProductId,
            Collectors.summingInt(Position::getCurrentStock)));
  }

  /**
   * Bulk reserved stocks for a list of product IDs — one aggregation call.
   */
  private Map<String, Integer> bulkReservedStock(List<String> productIds) {
    var agg = Aggregation.newAggregation(
        Aggregation.match(Criteria.where("status").in("PENDING", "IN_PROGRESS")),
        Aggregation.unwind("items"),
        Aggregation.match(Criteria.where("items.productId").in(productIds)),
        Aggregation.group("items.productId").sum("items.quantity").as("total")
    );
    AggregationResults<ProductStockSum> results =
        mongoTemplate.aggregate(agg, "orders", ProductStockSum.class);
    return results.getMappedResults().stream()
        .collect(Collectors.toMap(ProductStockSum::id, ProductStockSum::total));
  }

  // ── Product CRUD ───────────────────────────────────────────────────────────

  public Page<ProductResponse> getProducts(
      String category, String search, Boolean active, Pageable pageable) {
    Query query = new Query();
    query.addCriteria(Criteria.where("active").is(active != null ? active : true));
    if (category != null) query.addCriteria(Criteria.where("category").is(category));
    if (search != null && !search.isBlank()) {
      query.addCriteria(new Criteria().orOperator(
          Criteria.where("name").regex(search, "i"),
          Criteria.where("sku").regex(search, "i")));
    }
    long total = mongoTemplate.count(query, Product.class);
    List<Product> items = mongoTemplate.find(query.with(pageable), Product.class);

    List<String> ids = items.stream().map(Product::getId).toList();
    Map<String, Integer> available = bulkAvailableStock(ids);
    Map<String, Integer> reserved = bulkReservedStock(ids);

    List<ProductResponse> responses = items.stream()
        .map(p -> ProductResponse.from(p,
            available.getOrDefault(p.getId(), 0),
            reserved.getOrDefault(p.getId(), 0)))
        .toList();
    return new PageImpl<>(responses, pageable, total);
  }

  public ProductResponse getProduct(String id, Boolean isActive) {
    Product product = productRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!Boolean.FALSE.equals(isActive) && !product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    return ProductResponse.from(product,
        computeAvailableStock(id), computeReservedStock(id));
  }

  public ProductResponse createProduct(CreateProductRequest request) {
    if (productRepository.findBySku(request.sku()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }
    Product product = new Product();
    product.setSku(request.sku());
    product.setName(request.name());
    product.setDescription(request.description());
    product.setCategory(request.category());
    product.setImageUrl(request.imageUrl());
    product.setMaxQuantityPerOrder(request.maxQuantityPerOrder() != null ? request.maxQuantityPerOrder() : 0);
    product.setMinimumStock(request.minimumStock() != null ? request.minimumStock() : 0);
    product.setActive(true);
    product.setCreatedAt(Instant.now());
    try {
      Product saved = productRepository.save(product);
      return ProductResponse.from(saved, 0, 0);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }
  }

  public ProductResponse updateProduct(String id, UpdateProductRequest request) {
    Product product = productRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    if (request.name() != null) product.setName(request.name());
    if (request.description() != null) product.setDescription(request.description());
    if (request.category() != null) product.setCategory(request.category());
    if (request.imageUrl() != null) product.setImageUrl(request.imageUrl());
    if (request.maxQuantityPerOrder() != null) product.setMaxQuantityPerOrder(request.maxQuantityPerOrder());
    if (request.minimumStock() != null) product.setMinimumStock(request.minimumStock());
    if (request.isActive() != null) product.setActive(request.isActive());
    Product saved = productRepository.save(product);
    return ProductResponse.from(saved, computeAvailableStock(id), computeReservedStock(id));
  }

  public void deleteProduct(String id) {
    Product product = productRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    product.setActive(false);
    productRepository.save(product);
    // Clear all position assignments for this product
    positionRepository.findByProductIdIn(List.of(id)).forEach(p -> {
      p.setProductId(null);
      p.setCurrentStock(0);
      positionRepository.save(p);
    });
  }

  public List<ProductLocationEntry> getProductLocation(String id) {
    productRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    return positionRepository.findByProductIdIn(List.of(id)).stream()
        .map(ProductLocationEntry::from)
        .toList();
  }

  // ── Inner helpers ──────────────────────────────────────────────────────────

  private record StockSum(int total) {}
  private record ProductStockSum(String id, int total) {}

  public record ProductLocationEntry(
      String idPosition, String positionName, int currentStock,
      String idLine, String idZone) {
    public static ProductLocationEntry from(Position p) {
      return new ProductLocationEntry(
          p.getId(), p.getPositionName(), p.getCurrentStock(),
          p.getIdLine(), p.getIdZone());
    }
  }
}
```

- [ ] **Step 6: Update ProductController.java**

Replace the class body with (keeping package/imports):
```java
// Change return types that used Product → now ProductResponse comes from service
// Add GET /products/:id/location

  @GetMapping
  public ResponseEntity<Map<String, Object>> getProducts(...) {
    // Same pagination logic but service now returns Page<ProductResponse>
    Page<ProductResponse> result = productService.getProducts(category, search, isActive, pageable);
    return ResponseEntity.ok(Map.of(
        "products", result.getContent(),
        "pagination", Pagination.from(result)));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> getProduct(
      @PathVariable String id, @RequestParam(required = false) Boolean isActive) {
    return ResponseEntity.ok(Map.of("product", productService.getProduct(id, isActive)));
  }

  @PostMapping
  public ResponseEntity<Map<String, ProductResponse>> createProduct(
      @Valid @RequestBody CreateProductRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("product", productService.createProduct(request)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<Map<String, ProductResponse>> updateProduct(
      @PathVariable String id, @Valid @RequestBody UpdateProductRequest request) {
    return ResponseEntity.ok(Map.of("product", productService.updateProduct(id, request)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteProduct(@PathVariable String id) {
    productService.deleteProduct(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}/location")
  public ResponseEntity<Map<String, Object>> getProductLocation(@PathVariable String id) {
    return ResponseEntity.ok(Map.of("locations", productService.getProductLocation(id)));
  }
```

- [ ] **Step 7: Update ProductServiceTest.java**

Update the existing tests to remove references to `availableStock`, `reservedStock`, `zone`, `line`, `position`, `height` on Product, and mock `PositionRepository` where needed. Key changes:

```java
// Add to test class:
@Mock PositionRepository positionRepository;

// Replace activeProduct helper:
private Product activeProduct(String id) {
  Product p = new Product();
  p.setId(id);
  p.setName("Product " + id);
  p.setSku("SKU-" + id);
  p.setCategory("electronics");
  p.setMinimumStock(10);
  p.setActive(true);
  return p;
}

// In createProduct tests — remove availableStock/stock assertions
// In getProducts tests — mock positionRepository.findByProductIdIn to return empty list
// ProductService constructor now takes PositionRepository — update @InjectMocks or manual construction
```

- [ ] **Step 8: Update ProductControllerTest.java**

Remove `sampleProduct.setAvailableStock(10)` and `sampleProduct.setReservedStock(0)` from setUp. Update `when(productService.getProducts(...))` to return `Page<ProductResponse>` instead of `Page<Product>`.

- [ ] **Step 9: Run tests**

```bash
./gradlew test --tests "com.usal.whbackend.service.ProductServiceTest"
./gradlew test --tests "com.usal.whbackend.api.product.ProductControllerTest"
```
Expected: all pass.

- [ ] **Step 10: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: computed stock, product location endpoint, cascade delete"
```

---

## Task 10: Order Service Refactor — Computed Stock Check + FIFO Depletion

**Files:**
- Modify: `src/main/java/com/usal/whbackend/service/OrderService.java`
- Modify: `src/main/java/com/usal/whbackend/repository/kafka/OrderStatusConsumer.java`
- Modify: `src/test/java/com/usal/whbackend/service/OrderServiceTest.java`

- [ ] **Step 1: Replace createOrder and cancelOrder in OrderService.java**

The key changes:
1. Remove `productRepository.updateStock(...)` calls
2. Compute stock via `ProductService.computeAvailableStock()` and `computeReservedStock()`
3. Wrap `createOrder` in `@Transactional`

```java
// Add to imports:
import com.usal.whbackend.service.ProductService;
import org.springframework.transaction.annotation.Transactional;

// Add field and constructor param:
private final ProductService productService;

// Replace createOrder:
@Transactional
public Order createOrder(CreateOrderRequest request, String userId) {
  if (request.destinationArea() == null || request.destinationArea().isBlank()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DESTINATION_AREA_REQUIRED");
  }
  if (request.items() == null || request.items().isEmpty()) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED");
  }

  Set<String> seenProductIds = new HashSet<>();
  for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
    if (!seenProductIds.add(itemRequest.productId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT_IN_ORDER");
    }
  }

  List<OrderItem> items = new ArrayList<>();
  for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
    if (itemRequest.quantity() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }
    Product product = productRepository.findById(itemRequest.productId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE");
    }
    if (itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_LIMIT");
    }
    int available = productService.computeAvailableStock(product.getId());
    int reserved = productService.computeReservedStock(product.getId());
    if (available - reserved < itemRequest.quantity()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
    }
    items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
  }

  Order order = new Order();
  order.setStatus(OrderStatus.PENDING);
  order.setRequestedByUserId(userId);
  order.setItems(items);
  order.setDestinationArea(request.destinationArea());
  order.setCreatedAt(Instant.now());

  Order saved = orderRepository.save(order);
  orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
  return saved;
}

// Replace cancelOrder — remove updateStock calls:
public Order cancelOrder(String id, String reason) {
  Order order = orderRepository.findById(id)
      .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
  if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
  }
  // No stock fields to update — reserved is now computed from active orders
  Order cancelled = orderRepository.cancel(order, reason);
  orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(cancelled));
  return cancelled;
}
```

- [ ] **Step 2: Add FIFO stock depletion to OrderStatusConsumer.java**

Add fields for `PositionRepository`, `ProductRepository`, and `StockEventPublisher` list. Update the `"completed"` case in `applyStatus`:

```java
// Add constructor params:
private final PositionRepository positionRepository;
private final ProductRepository productRepository;
private final List<StockEventPublisher> stockEventPublishers;

// Replace applyStatus "completed" case:
case "completed" -> {
  order.setStatus(OrderStatus.COMPLETED);
  order.setCompletedAt(Instant.parse(msg.timestamp()));
  drainPositionStock(order);
}

// Add new method:
private void drainPositionStock(Order order) {
  if (order.getItems() == null) return;
  for (OrderItem item : order.getItems()) {
    int remaining = item.getQuantity();
    List<Position> positions = positionRepository
        .findByProductIdAndCurrentStockGreaterThanOrderByCreatedAtAsc(item.getProductId(), 0);
    for (Position position : positions) {
      if (remaining <= 0) break;
      int drain = Math.min(remaining, position.getCurrentStock());
      position.setCurrentStock(position.getCurrentStock() - drain);
      positionRepository.save(position);
      remaining -= drain;
    }
    // Check stock alert after depletion
    // NOTE: FIFO is a best-effort approximation; actual rover pick order may differ
    int totalStock = positionRepository
        .findByProductIdIn(List.of(item.getProductId())).stream()
        .mapToInt(Position::getCurrentStock).sum();
    productRepository.findById(item.getProductId()).ifPresent(product -> {
      if (totalStock < product.getMinimumStock()) {
        stockEventPublishers.forEach(p -> p.broadcastStockAlert(product, totalStock));
      }
    });
  }
}
```

- [ ] **Step 3: Update OrderServiceTest.java**

Remove all `verify(productRepository).updateStock(...)` assertions. Add `ProductService` as a mock. Update stock check tests to mock `productService.computeAvailableStock()` and `productService.computeReservedStock()`:

```java
@Mock ProductService productService;

// In setUp, add productService to OrderService constructor:
orderService = new OrderService(
    orderRepository, productRepository,
    List.of(orderEventPublisher), List.of(stockEventPublisher), productService);

// Replace any test that mocked product.getAvailableStock():
// Before: product.setAvailableStock(10);
// After:  when(productService.computeAvailableStock(product.getId())).thenReturn(10);
//         when(productService.computeReservedStock(product.getId())).thenReturn(0);
```

- [ ] **Step 4: Run tests**

```bash
./gradlew test --tests "com.usal.whbackend.service.OrderServiceTest"
```
Expected: all pass.

- [ ] **Step 5: Run full suite**

```bash
./gradlew test
```
Expected: all tests pass.

- [ ] **Step 6: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: computed stock order validation, fifo position drain on completion"
```

---

## Task 11: DataInitializer — Seed Warehouse Structure

**Files:**
- Modify: `src/main/java/com/usal/whbackend/config/DataInitializer.java`

- [ ] **Step 1: Update DataInitializer.java to seed zones, lines, positions**

Add constructor params for `ZoneRepository`, `LineRepository`, `PositionRepository`, `ProductRepository`. Add warehouse seed logic after the admin user seed:

```java
// Add to imports:
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ZoneRepository;

// Add fields and constructor params (keep existing ones):
private final ZoneRepository zoneRepository;
private final LineRepository lineRepository;
private final PositionRepository positionRepository;

// Add to run() after admin seed — only seed if no zones exist yet:
if (!zoneRepository.findAll().isEmpty()) return;

// Zone A
Zone zoneA = new Zone();
zoneA.setZoneCode("A");
zoneA.setMaxAllowedLines(10);
zoneA.setActive(true);
zoneA.setCreatedAt(Instant.now());
zoneA = zoneRepository.save(zoneA);

// Line 1 in Zone A
Line lineA1 = new Line();
lineA1.setIdZone(zoneA.getId());
lineA1.setNumberLine(1);
lineA1.setMaxAllowedPositions(20);
lineA1.setActive(true);
lineA1.setCreatedAt(Instant.now());
lineA1 = lineRepository.save(lineA1);

// Position P01 in Line A1
Position posA1P1 = new Position();
posA1P1.setIdLine(lineA1.getId());
posA1P1.setIdZone(zoneA.getId());
posA1P1.setPositionName("P01");
posA1P1.setMaximumCapacity(200);
posA1P1.setSizeStockToSave(StockSize.MEDIANO);
posA1P1.setActive(true);
posA1P1.setCurrentStock(0);
posA1P1.setCreatedAt(Instant.now());
positionRepository.save(posA1P1);

// Position P02 in Line A1
Position posA1P2 = new Position();
posA1P2.setIdLine(lineA1.getId());
posA1P2.setIdZone(zoneA.getId());
posA1P2.setPositionName("P02");
posA1P2.setMaximumCapacity(200);
posA1P2.setSizeStockToSave(StockSize.GRANDE);
posA1P2.setActive(true);
posA1P2.setCurrentStock(0);
posA1P2.setCreatedAt(Instant.now());
positionRepository.save(posA1P2);

log.info("Seeded warehouse structure: Zone A, Line 1, Positions P01-P02");
```

- [ ] **Step 2: Run the app and verify seed**

```bash
./gradlew bootRun &
sleep 15
curl -s http://localhost:8080/actuator/health | grep UP
curl -s -H "Authorization: Bearer $(curl -s -X POST http://localhost:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"'$ADMIN_EMAIL'","password":"'$ADMIN_PASSWORD'"}' | jq -r '.token')" \
  http://localhost:8080/warehouse/zones | jq .
```
Expected: `{"zones":[{"zone_code":"A",...}]}`

- [ ] **Step 3: Run full test suite with coverage**

```bash
./gradlew check
```
Expected: all tests pass, coverage ≥ 80%.

- [ ] **Step 4: Format and commit**

```bash
./gradlew spotlessApply
git add -A
git commit -m "feat: seed warehouse structure in data initializer"
```

---

## Final Step: Push and Open PR

- [ ] **Push branch**

```bash
git push origin feature/position-endpoints
```

- [ ] **Open PR against develop**

```bash
gh pr create \
  --base develop \
  --title "feat: warehouse module, computed stock, position-driven inventory" \
  --body "Implements Zone/Line/Position CRUD, migrates product stock to position-driven model, refactors order service to use computed stock with FIFO depletion on completion. See docs/specs/2026-05-26-warehouse-positions-design.md for full design rationale.

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```
