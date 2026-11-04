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
  void suggestionConversionIsListedWithItsRangeAndGranularity() {
    ComputedMetricDescriptor d =
        registry.computedVisibleTo(Set.of(UserRole.DASHBOARD)).stream()
            .filter(m -> m.name().equals("suggestion_conversion"))
            .findFirst()
            .orElseThrow();

    assertThat(d.path()).isEqualTo("/metrics/suggestion-conversion");
    assertThat(d.params())
        .extracting(ComputedMetricDescriptor.Field::name)
        .containsExactly("from", "to", "granularity");
    assertThat(d.filters())
        .extracting(ComputedMetricDescriptor.Field::name)
        .containsExactly("time_zone", "product_ids", "category");
  }

  @Test
  void computedMetricsAreHiddenFromRolesThatCannotReadMetrics() {
    assertThat(registry.computedVisibleTo(Set.of(UserRole.OPERATOR))).isEmpty();
  }

  private record Expected(MetricType type, List<String> dimensions) {}

  private static final Map<String, Expected> NEW_METRICS =
      Map.ofEntries(
          Map.entry(
              "http.server.duration",
              new Expected(MetricType.HISTOGRAM, List.of("route", "method", "status_class"))),
          Map.entry(
              "mongo.command.duration",
              new Expected(MetricType.HISTOGRAM, List.of("collection", "command", "outcome"))),
          Map.entry(
              "kafka.messages",
              new Expected(MetricType.COUNTER, List.of("topic", "direction", "result"))),
          Map.entry("ws.sessions", new Expected(MetricType.GAUGE, List.of("feed"))),
          Map.entry("ws.messages", new Expected(MetricType.COUNTER, List.of("feed", "result"))),
          Map.entry("jvm.heap.used", new Expected(MetricType.GAUGE, List.of())),
          Map.entry("jvm.threads", new Expected(MetricType.GAUGE, List.of())),
          Map.entry("jvm.gc.time", new Expected(MetricType.COUNTER, List.of("gc"))),
          Map.entry("tomcat.threads", new Expected(MetricType.GAUGE, List.of("state"))),
          Map.entry("tomcat.connections", new Expected(MetricType.GAUGE, List.of("state"))),
          Map.entry("http.server.active", new Expected(MetricType.GAUGE, List.of())),
          Map.entry("mongo.pool.connections", new Expected(MetricType.GAUGE, List.of("state"))),
          Map.entry("mongo.pool.checkout", new Expected(MetricType.HISTOGRAM, List.of())),
          Map.entry(
              "mongo.pool.checkout.failed", new Expected(MetricType.COUNTER, List.of("reason"))),
          Map.entry(
              "kafka.publish.duration",
              new Expected(MetricType.HISTOGRAM, List.of("topic", "result"))),
          Map.entry("bridge.messages", new Expected(MetricType.COUNTER, List.of("flow", "result"))),
          Map.entry(
              "bridge.callback.duration", new Expected(MetricType.HISTOGRAM, List.of("flow"))),
          Map.entry(
              "bridge.publish.duration",
              new Expected(MetricType.HISTOGRAM, List.of("flow", "result"))),
          Map.entry(
              "bridge.processing.duration",
              new Expected(MetricType.HISTOGRAM, List.of("flow", "result"))),
          Map.entry("bridge.consumer.lag", new Expected(MetricType.GAUGE, List.of("flow"))),
          Map.entry("job.run", new Expected(MetricType.GAUGE, List.of("job", "result", "field"))));

  @Test
  void everyNewMetricIsInTheCatalogue() {
    NEW_METRICS.forEach(
        (name, expected) -> {
          MetricDescriptor d = registry.findByName(name).orElseThrow();
          assertThat(d.dimensions()).as(name).isEqualTo(expected.dimensions());
          assertThat(d.type()).as(name).isEqualTo(expected.type());
          assertThat(d.requiredRoles()).as(name).isEqualTo(Set.of(UserRole.SUPERADMIN));
          assertThat(d.seriesName()).isEqualTo(name.replace('.', '_'));
        });
    assertThat(registry.findByName("kafka.messages").orElseThrow().permittedAggregations())
        .containsExactly(Aggregation.RATE, Aggregation.INCREASE);
    assertThat(registry.findByName("jvm.threads").orElseThrow().permittedAggregations())
        .containsExactly(Aggregation.AVG, Aggregation.MAX, Aggregation.LAST);
    assertThat(registry.findByName("kafka.publish.duration").orElseThrow().unit()).isEqualTo("ms");
    assertThat(registry.findByName("bridge.processing.duration").orElseThrow().unit())
        .isEqualTo("ms");
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
    assertThat(registry.findByName("wh.vehicle.battery").orElseThrow())
        .satisfies(
            d -> {
              assertThat(d.type()).isEqualTo(MetricType.GAUGE);
              assertThat(d.dimensions()).containsExactly("vehicle_id");
            });
    assertThat(registry.findByName("wh.vehicle.state").orElseThrow())
        .satisfies(
            d -> {
              assertThat(d.type()).isEqualTo(MetricType.GAUGE);
              assertThat(d.dimensions()).containsExactly("vehicle_id", "state");
            });
    assertThat(registry.findByName("wh.vehicle.transitions").orElseThrow())
        .satisfies(
            d -> {
              assertThat(d.type()).isEqualTo(MetricType.COUNTER);
              assertThat(d.dimensions()).containsExactly("vehicle_id", "from", "to", "category");
            });
  }
}
