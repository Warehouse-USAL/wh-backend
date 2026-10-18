package com.usal.whbackend.service.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.mock.env.MockEnvironment;

class ServiceStatusServiceTest {

  private static final List<String> NAMES =
      List.of(
          "backend",
          "bridge",
          "mongodb",
          "redpanda",
          "minio",
          "victoriametrics",
          "victorialogs",
          "otel-collector");

  private HttpServer server;
  private ServiceStatusService service;
  private ConsoleProperties properties;
  private HealthContributorRegistry registry;
  private MockEnvironment environment;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/ok",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/broken",
        exchange -> {
          exchange.sendResponseHeaders(503, -1);
          exchange.close();
        });
    server.createContext(
        "/slow",
        exchange -> {
          try {
            Thread.sleep(4000);
            exchange.sendResponseHeaders(200, -1);
          } catch (InterruptedException | IOException ignored) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();

    properties = new ConsoleProperties();
    properties.setBridgeUrl("");
    properties.setMinioUrl("");
    properties.setVictoriametricsUrl("");
    properties.setVictorialogsUrl("");
    properties.setCollectorUrl("");
    registry = new DefaultHealthContributorRegistry();
    registry.registerContributor("ping", (HealthIndicator) () -> Health.up().build());
    registry.registerContributor("mongo", (HealthIndicator) () -> Health.up().build());
    environment = new MockEnvironment();
    service = new ServiceStatusService(properties, registry, environment, "localhost:1");
  }

  @AfterEach
  void tearDown() {
    server.stop(0);
    service.shutdown();
  }

  private String url(String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private ServiceStatus find(List<ServiceStatus> statuses, String name) {
    return statuses.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void reportsAllEightServicesInOrder() {
    assertThat(service.statuses()).extracting(ServiceStatus::name).containsExactlyElementsOf(NAMES);
  }

  @Test
  void backendStatusCarriesUptime() {
    ServiceStatus backend = find(service.statuses(), "backend");

    assertThat(backend.state()).isEqualTo("UP");
    assertThat(backend.uptimeMs()).isNotNull().isPositive();
    assertThat(find(service.statuses(), "mongodb").uptimeMs()).isNull();
    assertThat(find(service.statuses(), "mongodb").state()).isEqualTo("UP");
  }

  @Test
  void statusReportsDevWhenProfileDevOrDocker() {
    assertThat(service.isDev()).isFalse();
    environment.setActiveProfiles("dev");
    assertThat(service.isDev()).isTrue();
    environment.setActiveProfiles("docker");
    assertThat(service.isDev()).isTrue();
    environment.setActiveProfiles("prod");
    assertThat(service.isDev()).isFalse();
  }

  @Test
  void upProbeIsUp() {
    properties.setMinioUrl(url("/ok"));
    properties.setVictorialogsUrl(url("/ok"));

    ServiceStatus minio = find(service.statuses(), "minio");

    assertThat(minio.state()).isEqualTo("UP");
  }

  @Test
  void downProbeIsReportedNotThrown() {
    properties.setBridgeUrl(url("/broken"));
    properties.setCollectorUrl("http://127.0.0.1:1");

    List<ServiceStatus> statuses = service.statuses();

    assertThat(find(statuses, "bridge").state()).isEqualTo("DOWN");
    assertThat(find(statuses, "bridge").detail()).isEqualTo("HTTP 503");
    ServiceStatus collector = find(statuses, "otel-collector");
    assertThat(collector.state()).isEqualTo("DOWN");
    assertThat(collector.detail()).isEqualTo("connection refused").doesNotContain("127.0.0.1");
  }

  @Test
  void slowProbeTimesOutAsDown() {
    properties.setVictoriametricsUrl(url("/slow"));
    long start = System.nanoTime();

    ServiceStatus vm = find(service.statuses(), "victoriametrics");

    assertThat(vm.state()).isEqualTo("DOWN");
    assertThat(vm.detail()).isEqualTo("timeout");
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
  }

  @Test
  void unconfiguredProbeIsUnknown() {
    ServiceStatus bridge = find(service.statuses(), "bridge");

    assertThat(bridge.state()).isEqualTo("UNKNOWN");
    assertThat(bridge.detail()).isEqualTo("not configured");
  }

  @Test
  void blankBootstrapMakesRedpandaUnknown() {
    ServiceStatusService noKafka = new ServiceStatusService(properties, registry, environment, "");
    try {
      assertThat(find(noKafka.statuses(), "redpanda").state()).isEqualTo("UNKNOWN");
    } finally {
      noKafka.shutdown();
    }
  }

  @Test
  void unreachableKafkaIsDownWithinBudget() {
    long start = System.nanoTime();

    ServiceStatus redpanda = find(service.statuses(), "redpanda");

    assertThat(redpanda.state()).isEqualTo("DOWN");
    assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
  }

  @Test
  void healthIndicatorProblemsAreDownOrUnknown() {
    registry.unregisterContributor("mongo");
    registry.unregisterContributor("ping");
    registry.registerContributor("mongo", (HealthIndicator) () -> Health.down().build());
    registry.registerContributor(
        "ping",
        (HealthIndicator)
            () -> {
              throw new IllegalStateException("secret mongodb://user:pw@host");
            });

    List<ServiceStatus> statuses = service.statuses();

    assertThat(find(statuses, "mongodb").state()).isEqualTo("DOWN");
    ServiceStatus backend = find(statuses, "backend");
    assertThat(backend.state()).isEqualTo("DOWN");
    assertThat(backend.detail()).doesNotContain("secret");
    assertThat(backend.uptimeMs()).isNotNull();
  }

  @Test
  void missingIndicatorIsUnknown() {
    registry.unregisterContributor("mongo");

    assertThat(find(service.statuses(), "mongodb").state()).isEqualTo("UNKNOWN");
  }
}
