package com.usal.whbackend.service.console;

import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Probes the eight platform services in parallel. The backend and MongoDB come from the actuator
 * health indicators in process, Redpanda from a short Kafka metadata call (Spring Boot ships no
 * Kafka health indicator) and the rest from an HTTP GET. Every probe is bounded by {@link #TIMEOUT}
 * and none throws: a failure is a {@code DOWN} row with a short detail, a blank URL is {@code
 * UNKNOWN}.
 */
@Service
public class ServiceStatusService {

  static final String UP = "UP";
  static final String DOWN = "DOWN";
  static final String UNKNOWN = "UNKNOWN";

  private static final Duration TIMEOUT = Duration.ofSeconds(2);
  private static final Duration KAFKA_TIMEOUT = Duration.ofMillis(1500);
  private static final Duration DEADLINE = Duration.ofMillis(2200);
  private static final Profiles DEV_PROFILES = Profiles.of("dev", "docker");

  private final ConsoleProperties properties;
  private final HealthContributorRegistry healthRegistry;
  private final Environment environment;
  private final String kafkaBootstrap;
  private final RestClient client;
  private final ExecutorService executor =
      Executors.newFixedThreadPool(
          8,
          runnable -> {
            Thread thread = new Thread(runnable, "service-status-probe");
            thread.setDaemon(true);
            return thread;
          });

  public ServiceStatusService(
      ConsoleProperties properties,
      HealthContributorRegistry healthRegistry,
      Environment environment,
      @Value("${spring.kafka.bootstrap-servers:}") String kafkaBootstrap) {
    this.properties = properties;
    this.healthRegistry = healthRegistry;
    this.environment = environment;
    this.kafkaBootstrap = kafkaBootstrap;
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(TIMEOUT);
    factory.setReadTimeout(TIMEOUT);
    this.client = RestClient.builder().requestFactory(factory).build();
  }

  /** True under the {@code dev} or {@code docker} Spring profile. */
  public boolean isDev() {
    return environment.acceptsProfiles(DEV_PROFILES);
  }

  /** Probes every service at once; always returns all eight rows, in a fixed order. */
  public List<ServiceStatus> statuses() {
    List<CompletableFuture<ServiceStatus>> futures = new ArrayList<>();
    futures.add(probe("backend", this::backend));
    futures.add(probe("bridge", () -> http("bridge", properties.getBridgeUrl(), "/healthz")));
    futures.add(probe("mongodb", () -> indicator("mongodb", "mongo")));
    futures.add(probe("redpanda", this::kafka));
    futures.add(
        probe("minio", () -> http("minio", properties.getMinioUrl(), "/minio/health/live")));
    futures.add(
        probe(
            "victoriametrics",
            () -> http("victoriametrics", properties.getVictoriametricsUrl(), "/health")));
    futures.add(
        probe(
            "victorialogs",
            () -> http("victorialogs", properties.getVictorialogsUrl(), "/health")));
    futures.add(
        probe("otel-collector", () -> http("otel-collector", properties.getCollectorUrl(), "")));
    return futures.stream().map(CompletableFuture::join).toList();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  private CompletableFuture<ServiceStatus> probe(String name, Supplier<ServiceStatus> check) {
    return CompletableFuture.supplyAsync(
            () -> {
              try {
                return check.get();
              } catch (RuntimeException e) {
                return new ServiceStatus(name, DOWN, "probe failed", null);
              }
            },
            executor)
        .completeOnTimeout(
            new ServiceStatus(name, DOWN, "timeout", null),
            DEADLINE.toMillis(),
            TimeUnit.MILLISECONDS);
  }

  private ServiceStatus backend() {
    ServiceStatus ping = indicator("backend", "ping");
    return new ServiceStatus(
        "backend", ping.state(), ping.detail(), ManagementFactory.getRuntimeMXBean().getUptime());
  }

  private ServiceStatus indicator(String name, String contributorName) {
    HealthContributor contributor = healthRegistry.getContributor(contributorName);
    if (!(contributor instanceof HealthIndicator health)) {
      return new ServiceStatus(name, UNKNOWN, "no health indicator", null);
    }
    try {
      boolean up = Status.UP.equals(health.health().getStatus());
      return new ServiceStatus(name, up ? UP : DOWN, up ? null : "health check reports down", null);
    } catch (RuntimeException e) {
      return new ServiceStatus(name, DOWN, "health check failed", null);
    }
  }

  private ServiceStatus kafka() {
    if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
      return notConfigured("redpanda");
    }
    int timeoutMs = (int) KAFKA_TIMEOUT.toMillis();
    try (Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs))) {
      admin.describeCluster().nodes().get(KAFKA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return new ServiceStatus("redpanda", UP, null, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ServiceStatus("redpanda", DOWN, "interrupted", null);
    } catch (Exception e) {
      return new ServiceStatus("redpanda", DOWN, "unreachable", null);
    }
  }

  private ServiceStatus http(String name, String baseUrl, String path) {
    if (baseUrl == null || baseUrl.isBlank()) {
      return notConfigured(name);
    }
    try {
      int code =
          client.get().uri(baseUrl + path).exchange((req, res) -> res.getStatusCode().value());
      return code >= 200 && code < 300
          ? new ServiceStatus(name, UP, null, null)
          : new ServiceStatus(name, DOWN, "HTTP " + code, null);
    } catch (ResourceAccessException e) {
      return new ServiceStatus(name, DOWN, transportDetail(e), null);
    } catch (RuntimeException e) {
      return new ServiceStatus(name, DOWN, "unreachable", null);
    }
  }

  private static String transportDetail(ResourceAccessException e) {
    Throwable cause = e.getCause();
    if (cause instanceof SocketTimeoutException) {
      return "timeout";
    }
    return cause instanceof ConnectException ? "connection refused" : "unreachable";
  }

  private static ServiceStatus notConfigured(String name) {
    return new ServiceStatus(name, UNKNOWN, "not configured", null);
  }
}
