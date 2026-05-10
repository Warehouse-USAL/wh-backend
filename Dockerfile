FROM eclipse-temurin:25-jdk AS build

WORKDIR /app

# Copy gradle wrapper and build files first for better layer caching
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
COPY build.gradle.kts settings.gradle.kts ./
COPY config/ config/

# Download dependencies (cached layer — only re-runs if build files change)
RUN ./gradlew dependencies --no-daemon 2>&1 | tail -3 || true

# Copy source and build
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test

# --- Runtime image ---
FROM eclipse-temurin:25-jre AS runtime

RUN groupadd -r appgroup && useradd -r -g appgroup appuser
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
