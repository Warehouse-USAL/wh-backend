.PHONY: help up-dev run up-prod up-infra down build test test-coverage lint format pr-checks logs clean

# Default target
.DEFAULT_GOAL := help

help: ## Show available commands
	@echo ""
	@echo "SmartWarehouse Backend — available commands:"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-20s\033[0m %s\n", $$1, $$2}'
	@echo ""

# ─── Docker targets ───────────────────────────────────────────────────────────

up-dev: ## Start all services in dev mode (app + MongoDB + Redpanda + UI tools)
	docker compose --profile dev up --build -d
	@echo ""
	@echo "Services started:"
	@echo "  Backend:          http://localhost:8080"
	@echo "  Swagger UI:       http://localhost:8080/swagger-ui.html"
	@echo "  MongoDB Express:  http://localhost:8081"
	@echo "  Redpanda Console: http://localhost:8082"
	@echo ""

run: up-dev ## Alias for up-dev

up-prod: ## Start services in production mode (requires env vars set)
	docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
	@echo "Production services started on port 8080"

up-infra: ## Start only MongoDB and Redpanda (for local Gradle development)
	docker compose up mongodb redpanda -d
	@echo ""
	@echo "Infrastructure started:"
	@echo "  MongoDB:  localhost:27017"
	@echo "  Redpanda: localhost:19092"
	@echo ""
	@echo "Run the app with: ./gradlew bootRun"

down: ## Stop and remove all containers
	docker compose --profile dev down

# ─── Build targets ────────────────────────────────────────────────────────────

build: ## Build the application JAR
	./gradlew bootJar
	@echo "JAR built at build/libs/"

clean: ## Clean build artifacts
	./gradlew clean

# ─── Test targets ─────────────────────────────────────────────────────────────

test: ## Run all tests
	./gradlew test

test-coverage: ## Run tests and generate JaCoCo coverage report
	./gradlew test jacocoTestReport
	@echo ""
	@echo "Coverage report: build/reports/jacoco/test/html/index.html"
	@open build/reports/jacoco/test/html/index.html 2>/dev/null || true

# ─── Quality targets ──────────────────────────────────────────────────────────

lint: ## Run Checkstyle and SpotBugs
	./gradlew checkstyleMain spotbugsMain

format: ## Check code formatting (Google Java Format via Spotless)
	./gradlew spotlessCheck

format-fix: ## Apply Google Java Format to all source files
	./gradlew spotlessApply

# ─── CI gate ──────────────────────────────────────────────────────────────────

pr-checks: ## Run all checks required before opening a PR (format + lint + test + build)
	@echo "Running pre-PR checks..."
	./gradlew spotlessCheck checkstyleMain spotbugsMain test bootJar
	@echo ""
	@echo "All checks passed. Ready to open a PR."

# ─── Observability ────────────────────────────────────────────────────────────

logs: ## Follow backend container logs
	docker compose logs -f backend
