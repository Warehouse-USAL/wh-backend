# SmartWarehouse Backend — Scaffolding Design

**Date:** 2026-05-10
**Branch:** `feature/init-repository`
**Source of truth:** `docs/RFC_SmartWarehouse_Backend.md`

---

## 1. Scope

This spec covers the scaffolding of the SmartWarehouse backend service. The goal is a runnable skeleton with correct architecture, all classes stubbed, Docker infrastructure wired, CI configured, and a Makefile that lets any contributor operate the project without prior knowledge of Java, Spring Boot, or Docker.

**In scope:**
- Gradle project setup (Kotlin DSL)
- Clean Architecture package structure with all domain classes stubbed
- Docker multi-stage build + docker-compose (dev and prod profiles)
- Makefile with dev and prod targets
- GitHub Actions CI (4 workflows, quiver.core-style)
- OpenAPI/Swagger config
- Auth infrastructure stubs (permit-all, no logic)
- ArchUnit dependency rule enforcement test

**Out of scope:**
- Business logic implementation
- JWT validation logic (auth is a separate task)
- Redpanda consumer/producer logic (separate task)
- WebSocket event broadcasting logic (separate task)

---

## 2. Tech Stack

| Component | Technology | Version |
|---|---|---|
| Language | Java | 25 LTS |
| Framework | Spring Boot | 4.0.6 |
| Build | Gradle (Kotlin DSL) | 8.x |
| Database | MongoDB | 7.x |
| Broker | Redpanda | latest |
| Auth | Spring Security + jjwt | 0.12.x (stubbed, permit-all) |
| WebSocket | Spring WebSocket (raw) | included in Spring Boot |
| API Docs | SpringDoc OpenAPI | 2.x |
| Testing | JUnit 5 + Mockito + Testcontainers + ArchUnit | included |
| Containers | Docker + Docker Compose | Compose v2 |

> Spring Boot 4.0 uses `springdoc-openapi-starter-webmvc-ui:2.x` — not the legacy `springfox` dependency listed in the RFC. The RFC dependency list will need a follow-up update.

---

## 3. Package Structure

Base package: `com.usal.whbackend`
Artifact ID: `wh-backend`
Group ID: `com.usal`

```
com.usal.whbackend/
│
├── domain/                          # Zero external dependencies
│   ├── order/
│   │   ├── Order.java
│   │   ├── OrderItem.java
│   │   └── OrderStatus.java         # enum: PENDING, IN_PROGRESS, COMPLETED, CANCELLED
│   ├── product/
│   │   ├── Product.java
│   │   ├── ProductLocation.java
│   │   └── Stock.java
│   ├── user/
│   │   ├── User.java
│   │   └── UserRole.java            # enum: ADMIN_SYSTEM, ADMIN_WAREHOUSE, ADMIN_SALES, ...
│   └── vehicle/
│       ├── Vehicle.java
│       └── VehicleStatus.java       # enum: IDLE, BUSY, OFFLINE, ERROR
│
├── application/                     # Imports domain only
│   ├── order/
│   │   ├── ports/
│   │   │   ├── OrderRepository.java
│   │   │   └── OrderEventPublisher.java
│   │   ├── usecases/
│   │   │   ├── CreateOrderUseCase.java          # interface
│   │   │   ├── CreateOrderUseCaseImpl.java      # stub
│   │   │   ├── CancelOrderUseCase.java          # interface
│   │   │   └── CancelOrderUseCaseImpl.java      # stub
│   │   └── dto/
│   │       ├── CreateOrderRequest.java
│   │       └── OrderResponse.java
│   ├── product/
│   │   ├── ports/
│   │   │   └── ProductRepository.java
│   │   ├── usecases/
│   │   │   ├── GetProductsUseCase.java
│   │   │   ├── GetProductsUseCaseImpl.java
│   │   │   ├── CreateProductUseCase.java
│   │   │   ├── CreateProductUseCaseImpl.java
│   │   │   ├── UpdateProductUseCase.java
│   │   │   ├── UpdateProductUseCaseImpl.java
│   │   │   ├── DeleteProductUseCase.java
│   │   │   └── DeleteProductUseCaseImpl.java
│   │   └── dto/
│   │       ├── CreateProductRequest.java
│   │       ├── UpdateProductRequest.java
│   │       └── ProductResponse.java
│   ├── user/
│   │   ├── ports/
│   │   │   └── UserRepository.java
│   │   ├── usecases/
│   │   │   ├── GetUsersUseCase.java
│   │   │   ├── GetUsersUseCaseImpl.java
│   │   │   ├── CreateUserUseCase.java
│   │   │   ├── CreateUserUseCaseImpl.java
│   │   │   ├── UpdateUserUseCase.java
│   │   │   ├── UpdateUserUseCaseImpl.java
│   │   │   ├── ResetPasswordUseCase.java
│   │   │   └── ResetPasswordUseCaseImpl.java
│   │   └── dto/
│   │       ├── CreateUserRequest.java
│   │       ├── UpdateUserRequest.java
│   │       └── UserResponse.java
│   ├── vehicle/
│   │   ├── ports/
│   │   │   └── VehicleRepository.java
│   │   ├── usecases/
│   │   │   ├── GetVehiclesUseCase.java
│   │   │   ├── GetVehiclesUseCaseImpl.java
│   │   │   ├── RegisterVehicleUseCase.java
│   │   │   └── RegisterVehicleUseCaseImpl.java
│   │   └── dto/
│   │       ├── RegisterVehicleRequest.java
│   │       └── VehicleResponse.java
│   └── auth/
│       ├── ports/
│       │   └── AuthUserRepository.java
│       ├── usecases/
│       │   ├── LoginUseCase.java
│       │   └── LoginUseCaseImpl.java            # stub — returns 501
│       └── dto/
│           ├── LoginRequest.java
│           └── LoginResponse.java
│
├── infrastructure/                  # Imports application only
│   ├── mongodb/
│   │   ├── order/
│   │   │   ├── OrderDocument.java               # @Document("orders")
│   │   │   ├── OrderMongoRepository.java        # extends MongoRepository
│   │   │   └── OrderMongoAdapter.java           # implements OrderRepository
│   │   ├── product/
│   │   │   ├── ProductDocument.java
│   │   │   ├── ProductMongoRepository.java
│   │   │   └── ProductMongoAdapter.java
│   │   ├── user/
│   │   │   ├── UserDocument.java
│   │   │   ├── UserMongoRepository.java
│   │   │   └── UserMongoAdapter.java
│   │   └── vehicle/
│   │       ├── VehicleDocument.java
│   │       ├── VehicleMongoRepository.java
│   │       └── VehicleMongoAdapter.java
│   ├── redpanda/
│   │   ├── OrderEventAdapter.java               # implements OrderEventPublisher (stub)
│   │   └── RedpandaConfig.java
│   ├── security/
│   │   ├── JwtService.java                      # stub — not implemented
│   │   ├── JwtAuthFilter.java                   # stub — passes all requests
│   │   ├── SecurityConfig.java                  # permit-all, no CSRF
│   │   └── UserDetailsServiceImpl.java          # stub
│   ├── websocket/
│   │   ├── OrderWebSocketHandler.java           # stub
│   │   ├── UserOrderWebSocketHandler.java       # stub
│   │   ├── VehicleWebSocketHandler.java         # stub
│   │   ├── StockAlertWebSocketHandler.java      # stub
│   │   └── WebSocketConfig.java                 # registers all 4 endpoints
│   └── config/
│       └── MongoConfig.java
│
└── api/                             # Imports application only
    ├── order/
    │   └── OrderController.java     # GET /orders, GET /orders/:id, POST /orders, POST /orders/:id/cancel
    ├── product/
    │   └── ProductController.java   # GET /products, GET /products/:id, POST, PATCH, DELETE
    ├── user/
    │   └── UserController.java      # GET /users, GET /users/:id, POST, PATCH, POST reset-password
    ├── vehicle/
    │   └── VehicleController.java   # GET /vehicles, GET /vehicles/:id, POST
    ├── auth/
    │   └── AuthController.java      # POST /auth/login (returns 501 stub)
    └── config/
        └── OpenApiConfig.java       # SpringDoc beans, API metadata
```

### Naming conventions (from RFC)

| Layer | Pattern | Example |
|---|---|---|
| domain | `{Entity}.java` | `Order.java` |
| application port | `{Entity}Repository.java` | `OrderRepository.java` |
| application use case (interface) | `{Action}{Entity}UseCase.java` | `CreateOrderUseCase.java` |
| application use case (impl) | `{Action}{Entity}UseCaseImpl.java` | `CreateOrderUseCaseImpl.java` |
| application DTO in | `{Action}{Entity}Request.java` | `CreateOrderRequest.java` |
| application DTO out | `{Entity}Response.java` | `OrderResponse.java` |
| infrastructure MongoDB doc | `{Entity}Document.java` | `OrderDocument.java` |
| infrastructure adapter | `{Entity}MongoAdapter.java` | `OrderMongoAdapter.java` |
| api controller | `{Entity}Controller.java` | `OrderController.java` |

### Dependency rule

Enforced at test time via ArchUnit:
- `domain` imports nothing external
- `application` imports `domain` only
- `infrastructure` imports `application` only
- `api` imports `application` only
- No layer imports from a layer above it

---

## 4. Docker Setup

### Dockerfile (multi-stage)

```dockerfile
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app
COPY . .
RUN ./gradlew bootJar --no-daemon

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### docker-compose.yml (dev profile — default)

Services:
- `backend` — built from `Dockerfile`, port `8080`
- `mongodb` — `mongo:7`, port `27017`, named volume
- `mongo-express` — `mongo-express:latest`, port `8081`, dev profile only
- `redpanda` — `redpandadata/redpanda:latest`, ports `9092`, `9644`
- `redpanda-console` — `redpandadata/console:latest`, port `8082`, dev profile only

### docker-compose.prod.yml (prod override)

Overrides:
- No `mongo-express`, no `redpanda-console`
- `backend` reads env vars from environment (no hardcoded defaults)
- MongoDB uses auth credentials from env
- Restart policies: `unless-stopped`

### Environment variables

| Variable | Description | Dev default |
|---|---|---|
| `SPRING_DATA_MONGODB_URI` | MongoDB connection URI | `mongodb://mongodb:27017/smartwarehouse` |
| `JWT_SECRET` | JWT signing secret | `dev-secret-change-in-prod-min-32-chars` |
| `JWT_EXPIRATION_MS` | Token TTL in ms | `86400000` |
| `REDPANDA_BOOTSTRAP_SERVERS` | Redpanda bootstrap | `redpanda:9092` |
| `SPRING_PROFILES_ACTIVE` | Spring profile | `dev` |

---

## 5. Makefile

```makefile
make up-dev       # docker compose up (dev profile: all services + UI tools)
make run          # alias for up-dev
make up-prod      # docker compose -f docker-compose.yml -f docker-compose.prod.yml up
make up-infra     # only mongodb + redpanda (no app container, for local Gradle run)
make down         # docker compose down
make build        # ./gradlew bootJar
make test         # ./gradlew test
make test-coverage # ./gradlew test jacocoTestReport (opens HTML report)
make lint         # ./gradlew checkstyleMain spotbugsMain
make format       # ./gradlew googleJavaFormat (check mode)
make pr-checks    # format + lint + test + build (full gate before opening a PR)
make logs         # docker compose logs -f backend
make clean        # ./gradlew clean
```

---

## 6. CI — GitHub Actions

4 workflows, adapted from quiver.core for Java/Spring Boot.

### `ci.yml` — triggered on PR to `develop` or `master`

Jobs:
1. **Validate Branch Model** — enforces `feature/`, `fix/`, `enhancement/`, `refactor/`, `hotfix/`, `beta/`, `backport/`, `dependabot/` prefixes
2. **Code Quality** — Checkstyle + SpotBugs
3. **Test Coverage** — runs in Docker (Java 25), JaCoCo gate at **80%**
4. **Build Validation** — `./gradlew build`, verifies JAR produced
5. Draft PRs skip all jobs (cost optimization)

### `prerelease.yml` — triggered on push to `beta/**` or `hotfix/**`

Jobs:
1. Build JAR
2. Build and tag Docker image
3. Create GitHub pre-release with JAR attached

### `stable-release.yml` — triggered on PR merged to `master` from `beta/` or `hotfix/`

Jobs:
1. Build and publish stable Docker image
2. Create stable GitHub release
3. Trigger backport workflow

### `backport.yml` — reusable, called by `stable-release.yml`

Jobs:
1. Create `backport/YYYY-MM-DD-VERSION` branch
2. Open PR to `develop`
3. Attempt auto-merge, comment if conflict

### Branch model

```
feature/* ──→ develop ──→ beta/* ──→ master
                              ↓ (auto-backport)
                           develop
```

---

## 7. Scaffolding Rules

- All `*UseCaseImpl` methods throw `UnsupportedOperationException("not implemented")`
- All controllers return `ResponseEntity` with the correct stub HTTP status: `501 Not Implemented` for auth endpoints, `200 OK` with an empty body for all others
- `SecurityConfig` sets `httpSecurity.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())`
- `JwtAuthFilter` is registered but passes every request through
- No Flyway/Liquibase — MongoDB is schema-less, indices defined via `@CompoundIndex` annotations on documents
- `application.yml` has `dev` and `prod` profile variants
- ArchUnit test lives at `src/test/java/com/usal/whbackend/ArchitectureTest.java`

---

## 8. Testing Scaffold

- `ArchitectureTest.java` — enforces dependency rules via ArchUnit (this test must pass from day one)
- One smoke test per controller (Spring Boot test slice, `@WebMvcTest`) returning the expected stub status
- `MongoConfig` and `RedpandaConfig` use `@TestContainers` in integration test base class
- JaCoCo configured in `build.gradle.kts`, minimum coverage set to 80% in CI (not enforced locally)
