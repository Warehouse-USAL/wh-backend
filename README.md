# SmartWarehouse Backend

Backend service for the SmartWarehouse autonomous warehouse system. Built with Spring Boot 4, MongoDB, and Redpanda (Kafka-compatible). Exposes a REST API, WebSocket channels for real-time events, and integrates with autonomous vehicle controllers.

## Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25 / Spring Boot 4.0 |
| Database | MongoDB 7 |
| Message broker | Redpanda (Kafka-compatible) |
| Auth | Spring Security + JWT |
| API docs | SpringDoc OpenAPI (Swagger UI) |
| Containerization | Docker + Docker Compose |

---

## Getting started

The only prerequisite is **Docker**. No Java, no Gradle, no MongoDB installation needed.

```bash
git clone https://github.com/Warehouse-USAL/wh-backend.git
cd wh-backend
cp src/main/resources/application.yml.example src/main/resources/application.yml
make up-dev
```

The stack will be available at:

| Service | URL |
|---|---|
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health check | http://localhost:8080/actuator/health |
| MongoDB Express | http://localhost:8081 |
| Redpanda Console | http://localhost:8082 |

---

## Makefile commands

```bash
make up-dev        # Start the full dev stack (app + MongoDB + Redpanda + UI tools)
make down          # Stop all containers
make up-infra      # Start only MongoDB and Redpanda (without the app)
make up-prod       # Start in production mode (requires env vars set)

make test          # Run all tests
make test-coverage # Run tests and open JaCoCo coverage report
make lint          # Run Checkstyle and SpotBugs
make format        # Check code formatting (Google Java Format)
make format-fix    # Auto-format all source files
make pr-checks     # Run all checks required before opening a PR

make build         # Build the application JAR
make clean         # Clean build artifacts
make logs          # Follow backend container logs
make help          # List all available commands
```

> All `make` commands that invoke Gradle run inside Docker — no local JDK required.

---

## Gitflow

```
feature/* ──► develop ──► beta/* ──► master
                              │
                         hotfix/* ──► master
```

| Branch | Purpose |
|---|---|
| `master` | Stable production releases only |
| `develop` | Integration branch — all features merge here |
| `beta/*` | Release candidates (e.g. `beta/1.0`) |
| `hotfix/*` | Emergency fixes that go directly to `master` |
| `feature/*` | New features |
| `fix/*` | Bug fixes |
| `refactor/*` | Refactors with no behavior change |
| `enhancement/*` | Improvements to existing features |

**The CI enforces branch naming.** PRs from branches that don't match these prefixes will fail automatically.

---

## Opening a PR

1. **Branch off `develop`** using one of the prefixes above.
2. Run `make pr-checks` locally before pushing — this runs the exact same checks as CI.
3. Open the PR targeting `develop` (never directly to `master`).
4. Keep PRs focused. One concern per PR.

### PR requirements (enforced by CI)

| Check | Tool | Requirement |
|---|---|---|
| Code formatting | Spotless (Google Java Format) | Must pass — run `make format-fix` to auto-fix |
| Static analysis | Checkstyle + SpotBugs | Zero violations |
| Tests | JUnit 5 + Mockito | All tests must pass |
| Coverage | JaCoCo | Minimum **80%** instruction coverage |
| Architecture | ArchUnit | Services must not depend on controllers; repositories must not depend on services or controllers |

Draft PRs are skipped by CI until marked ready for review.

---

## Project structure

```
src/main/java/com/usal/whbackend/
├── domain/          # Entities — plain Java classes annotated with @Document
├── repository/      # Spring Data MongoDB interfaces + Kafka producers/consumers
├── service/         # Business logic — @Service classes
├── api/             # Controllers, DTOs (records), WebSocket handlers
│   ├── auth/
│   ├── order/
│   ├── product/
│   ├── user/
│   ├── vehicle/
│   └── websocket/
└── config/          # Spring configuration (Security, MongoDB, WebSocket, OpenAPI)
```

Dependencies flow in one direction: `api → service → repository → domain`. The ArchUnit tests enforce this at build time.

---

## Architecture rules

- **Controllers** call **services** only — never repositories directly.
- **Services** call **repositories** only — never controllers.
- **Domain** classes are pure Java — no framework annotations except `@Document` and `@Id`.
- DTOs are **Java Records** and live next to their controller in `api/<domain>/`.
- All service methods that are not yet implemented throw `UnsupportedOperationException` — replace the throw with real logic when implementing.

---

## Data persistence

MongoDB and Redpanda data is persisted to `data/` at the project root (bind-mounted into Docker). This directory is gitignored. Delete it to reset all local state.

---

## Environment configuration

Copy `src/main/resources/application.yml.example` to `application.yml` and adjust as needed. This file is gitignored — never commit secrets.

When running via Docker Compose, connection strings are set automatically via the `docker` Spring profile (`application-docker.yml`). No manual configuration is needed for `make up-dev`.
