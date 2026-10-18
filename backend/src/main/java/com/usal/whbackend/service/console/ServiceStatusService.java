package com.usal.whbackend.service.console;

import jakarta.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(ServiceStatusService.class);

  private final ConsoleProperties properties;
  private final HealthContributorRegistry healthRegistry;
  private final Environment environment;
  private final String kafkaBootstrap;
  private final RestClient client;
  private final Map<String, AtomicBoolean> inFlight = new ConcurrentHashMap<>();
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
    long deadline = System.nanoTime() + DEADLINE.toNanos();
    List<Probe> probes = new ArrayList<>();
    probes.add(start("backend", this::backend));
    probes.add(start("bridge", () -> http("bridge", properties.getBridgeUrl(), "/healthz")));
    probes.add(start("mongodb", () -> indicator("mongodb", "mongo")));
    probes.add(start("redpanda", this::kafka));
    probes.add(start("minio", () -> http("minio", properties.getMinioUrl(), "/minio/health/live")));
    probes.add(
        start(
            "victoriametrics",
            () -> http("victoriametrics", properties.getVictoriametricsUrl(), "/health")));
    probes.add(
        start(
            "victorialogs",
            () -> http("victorialogs", properties.getVictorialogsUrl(), "/health")));
    probes.add(
        start("otel-collector", () -> http("otel-collector", properties.getCollectorUrl(), "")));
    return probes.stream().map(probe -> probe.await(deadline)).toList();
  }

  @PreDestroy
  void shutdown() {
    executor.shutdownNow();
  }

  /**
   * Submits a probe unless the previous one for the same service is still running, which bounds the
   * pool to one thread per service even when a probe (such as an in-process health check) hangs.
   */
  private Probe start(String name, Supplier<ServiceStatus> check) {
    AtomicBoolean busy = inFlight.computeIfAbsent(name, key -> new AtomicBoolean());
    if (!busy.compareAndSet(false, true)) {
      return new Probe(name, null, busy, new AtomicBoolean());
    }
    AtomicBoolean started = new AtomicBoolean();
    Future<ServiceStatus> future =
        executor.submit(
            () -> {
              started.set(true);
              try {
                return check.get();
              } catch (RuntimeException e) {
                log.debug("Status probe {} failed: {}", name, e.getClass().getSimpleName());
                return new ServiceStatus(name, DOWN, "probe failed", null);
              } finally {
                busy.set(false);
              }
            });
    return new Probe(name, future, busy, started);
  }

  private record Probe(
      String name, Future<ServiceStatus> future, AtomicBoolean busy, AtomicBoolean started) {

    ServiceStatus await(long deadlineNanos) {
      ServiceStatus timeout = new ServiceStatus(name, DOWN, "timeout", null);
      if (future == null) {
        return timeout;
      }
      try {
        return future.get(Math.max(0, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return timeout;
      } catch (ExecutionException | TimeoutException e) {
        future.cancel(true);
        if (!started.get()) {
          busy.set(false);
        }
        return timeout;
      }
    }
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
      Status status = health.health().getStatus();
      if (Status.UP.equals(status)) {
        return new ServiceStatus(name, UP, null, null);
      }
      if (Status.UNKNOWN.equals(status)) {
        return new ServiceStatus(name, UNKNOWN, "health check unknown", null);
      }
      return new ServiceStatus(name, DOWN, "health check reports down", null);
    } catch (RuntimeException e) {
      return new ServiceStatus(name, DOWN, "health check failed", null);
    }
  }

  private ServiceStatus kafka() {
    if (kafkaBootstrap == null || kafkaBootstrap.isBlank()) {
      return notConfigured("redpanda");
    }
    int timeoutMs = (int) KAFKA_TIMEOUT.toMillis();
    Admin admin =
        Admin.create(
            Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs));
    try {
      admin.describeCluster().nodes().get(KAFKA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      return new ServiceStatus("redpanda", UP, null, null);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new ServiceStatus("redpanda", DOWN, "interrupted", null);
    } catch (Exception e) {
      return new ServiceStatus("redpanda", DOWN, "unreachable", null);
    } finally {
      admin.close(Duration.ofMillis(500));
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
