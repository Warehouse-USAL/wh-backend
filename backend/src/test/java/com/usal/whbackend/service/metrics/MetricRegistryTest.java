package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricRegistryTest {

  private final MetricRegistry registry = new MetricRegistry();

  @Test
  void restockSuggestionsIsListedWithEveryRequiredParam() {
    ComputedMetricDescriptor d =
        registry.computedVisibleTo(Set.of(UserRole.DASHBOARD)).stream()
            .filter(m -> m.name().equals("restock_suggestions"))
            .findFirst()
            .orElseThrow();

    assertThat(d.path()).isEqualTo("/metrics/restock-suggestions");
    assertThat(d.params())
        .extracting(ComputedMetricDescriptor.Field::name)
        .containsExactly(
            "alpha", "recent_days", "long_days", "safety_days", "lead_time_days", "coverage_days");
  }

  @Test
  void computedMetricsAreHiddenFromRolesThatCannotReadMetrics() {
    assertThat(registry.computedVisibleTo(Set.of(UserRole.OPERATOR))).isEmpty();
  }

  private static final Map<String, List<String>> NEW_METRICS =
      Map.ofEntries(
          Map.entry("http.server.duration", List.of("route", "method", "status_class")),
          Map.entry("mongo.command.duration", List.of("collection", "command", "outcome")),
          Map.entry("kafka.messages", List.of("topic", "direction", "result")),
          Map.entry("ws.sessions", List.of("feed")),
          Map.entry("ws.messages", List.of("feed", "result")),
          Map.entry("jvm.heap.used", List.of()),
          Map.entry("jvm.threads", List.of()),
          Map.entry("jvm.gc.time", List.of("gc")),
          Map.entry("tomcat.threads", List.of("state")),
          Map.entry("tomcat.connections", List.of("state")),
          Map.entry("http.server.active", List.of()),
          Map.entry("mongo.pool.connections", List.of("state")),
          Map.entry("mongo.pool.checkout", List.of()),
          Map.entry("bridge.messages", List.of("flow", "result")),
          Map.entry("bridge.callback.duration", List.of("flow")),
          Map.entry("bridge.consumer.lag", List.of("flow")),
          Map.entry("job.run", List.of("job", "result", "field")));

  @Test
  void everyNewMetricIsInTheCatalogue() {
    NEW_METRICS.forEach(
        (name, dims) -> {
          MetricDescriptor d = registry.findByName(name).orElseThrow();
          assertThat(d.dimensions()).as(name).isEqualTo(dims);
          assertThat(d.seriesName()).isEqualTo(name.replace('.', '_'));
        });
    assertThat(registry.findByName("http.server.duration").orElseThrow().type())
        .isEqualTo(MetricType.HISTOGRAM);
    assertThat(registry.findByName("kafka.messages").orElseThrow().permittedAggregations())
        .containsExactly(Aggregation.RATE, Aggregation.INCREASE);
    assertThat(registry.findByName("jvm.threads").orElseThrow().permittedAggregations())
        .containsExactly(Aggregation.AVG, Aggregation.MAX, Aggregation.LAST);
  }

  @Test
  void newMetricsAreSuperadminOnly() {
    List<String> visible =
        registry.visibleTo(Set.of(UserRole.ADMIN_SYSTEM)).stream()
            .map(MetricDescriptor::name)
            .toList();
    assertThat(visible).doesNotContainAnyElementsOf(NEW_METRICS.keySet());
    assertThat(registry.visibleTo(Set.of(UserRole.SUPERADMIN)).stream().map(MetricDescriptor::name))
        .containsAll(NEW_METRICS.keySet());
  }

  @Test
  void existingVehicleMetricsUnchanged() {
    assertThat(registry.visibleTo(Set.of(UserRole.ADMIN_SYSTEM)))
        .extracting(MetricDescriptor::name)
        .containsExactly("wh.vehicle.battery", "wh.vehicle.state", "wh.vehicle.transitions");
  }
}
