.PHONY: e2e bridge-test help up-dev run up-prod up-infra deploy down build test test-coverage lint format format-fix pr-checks logs clean

.DEFAULT_GOAL := help

GRADLE = docker run --rm \
	-v "$(PWD)/backend":/app \
	-v wh-gradle-cache:/root/.gradle \
	-w /app \
	eclipse-temurin:21-jdk-alpine ./gradlew

help: ## Show available commands
	@echo ""
	@echo "SmartWarehouse Backend — available commands:"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ─── Docker targets ───────────────────────────────────────────────────────────

up-dev: ## Start all services in dev mode (app + MongoDB + Redpanda + UI tools)
	docker compose --profile dev up --build
	@echo ""
	@echo "Services started:"
	@echo "  Backend:          http://localhost:8080"
	@echo "  Swagger UI:       http://localhost:8080/swagger-ui.html"
	@echo "  MongoDB Express:  http://localhost:8081"
	@echo "  Redpanda Console: http://localhost:8082"
	@echo "  MinIO Console:    http://localhost:9001"
	@echo "  Backend console:  http://localhost:8083/console/ (SUPERADMIN)"
	@echo ""

run: up-dev ## Alias for up-dev

up-prod: ## Start services in production mode (requires env vars set)
	docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
	@echo "Production services started on port 8080"

PROD_COMPOSE = docker compose -f docker-compose.yml -f docker-compose.prod.yml

deploy: ## Pull the newest backend + bridge + console images and restart them, plus restock-cron (data untouched)
	$(PROD_COMPOSE) pull backend bridge console
	@# Telemetry first (docs/CONSOLE.md, Rollout): the collector's logs pipeline must be up before the
	@# new backend and bridge export to it. Recreated only when their compose config changed; the
	@# collector always, since its config is a bind mount compose cannot diff. Volumes persist.
	@# MongoDB and Redpanda are never touched. WH_IMAGE_TAG in .env pins the three app images.
	$(PROD_COMPOSE) up -d --no-deps victorialogs
	$(PROD_COMPOSE) up -d --no-deps --force-recreate otel-collector
	$(PROD_COMPOSE) up -d --no-deps minio victoriametrics
	$(PROD_COMPOSE) up -d --no-deps backend bridge console restock-cron
	@echo "Backend redeployed"

up-infra: ## Start only MongoDB, Redpanda and MinIO (for local Gradle development)
	docker compose up mongodb redpanda minio -d
	@echo ""
	@echo "Infrastructure started:"
	@echo "  MongoDB:         localhost:27017"
	@echo "  Redpanda:        localhost:19092"
	@echo "  MinIO API:       http://localhost:9000"
	@echo "  MinIO Console:   http://localhost:9001"
	@echo ""
	@echo "Run the app with: make build && make run"

down: ## Stop and remove all containers
	docker compose --profile dev down

# ─── Build targets ────────────────────────────────────────────────────────────

build: ## Build the application JAR
	$(GRADLE) bootJar
	@echo "JAR built at backend/build/libs/"

clean: ## Clean build artifacts
	$(GRADLE) clean

# ─── Test targets ─────────────────────────────────────────────────────────────

test: ## Run all tests
	$(GRADLE) test

test-coverage: ## Run tests and generate JaCoCo coverage report
	$(GRADLE) test jacocoTestReport
	@echo ""
	@echo "Coverage report: backend/build/reports/jacoco/test/html/index.html"
	@open backend/build/reports/jacoco/test/html/index.html 2>/dev/null || true

# ─── Quality targets ──────────────────────────────────────────────────────────

lint: ## Run Checkstyle and SpotBugs
	$(GRADLE) checkstyleMain spotbugsMain

format: ## Check code formatting (Google Java Format via Spotless)
	$(GRADLE) spotlessCheck

format-fix: ## Apply Google Java Format to all source files
	$(GRADLE) spotlessApply

# ─── Bridge / E2E ─────────────────────────────────────────────────────────────

bridge-test: ## Run the Go bridge's vet + tests
	cd bridge && go vet ./... && go test -race ./...

e2e: ## Full-stack dry run: backend <-> bridge <-> fake Infra (needs Docker; see e2e/run.sh)
	./e2e/run.sh

# ─── CI gate ──────────────────────────────────────────────────────────────────

pr-checks: ## Run all checks required before opening a PR (format + lint + test + build)
	@echo "Running pre-PR checks..."
	$(GRADLE) spotlessCheck checkstyleMain spotbugsMain test bootJar
	@echo ""
	@echo "All checks passed. Ready to open a PR."

# ─── Observability ────────────────────────────────────────────────────────────

logs: ## Follow backend container logs
	docker compose logs -f backend
