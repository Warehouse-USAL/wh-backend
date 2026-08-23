package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MetricsQueryTranslatorTest {

  private final MetricsQueryTranslator translator = new MetricsQueryTranslator();

  private static final MetricDescriptor BATTERY =
      new MetricDescriptor(
          "wh.vehicle.battery",
          "wh_vehicle_battery",
          "Vehicle battery",
          "%",
          MetricType.GAUGE,
          List.of("vehicle_id"),
          List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX, Aggregation.LAST),
          Set.of(UserRole.DASHBOARD));

  private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-21T00:00:00Z");

  private static MetricsQueryRequest request(
      String step, Map<String, String> filters, List<String> groupBy, String agg) {
    return new MetricsQueryRequest("wh.vehicle.battery", FROM, TO, step, filters, groupBy, agg);
  }

  private String translate(MetricsQueryRequest r) {
    return translator.translate(r, BATTERY);
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  @Test
  void groupsBySelectedDimension() {
    assertThat(translate(request("5m", Map.of(), List.of("vehicle_id"), "avg")))
        .isEqualTo("avg by (vehicle_id)(avg_over_time(wh_vehicle_battery[5m]))");
  }

  @Test
  void appliesFiltersAsLabelMatchers() {
    assertThat(translate(request("5m", Map.of("vehicle_id", "VHC-001"), List.of(), "avg")))
        .isEqualTo("avg(avg_over_time(wh_vehicle_battery{vehicle_id=\"VHC-001\"}[5m]))");
  }

  @Test
  void mapsEachAggregationToItsOverTimeFunction() {
    assertThat(translate(request("1h", Map.of(), List.of(), "min")))
        .isEqualTo("min(min_over_time(wh_vehicle_battery[1h]))");
    assertThat(translate(request("1h", Map.of(), List.of(), "max")))
        .isEqualTo("max(max_over_time(wh_vehicle_battery[1h]))");
    // MetricsQL has no `last` operator; last_over_time carries the semantics, max is the outer
    // stand-in and is identity whenever group_by covers every dimension.
    assertThat(translate(request("1h", Map.of(), List.of("vehicle_id"), "last")))
        .isEqualTo("max by (vehicle_id)(last_over_time(wh_vehicle_battery[1h]))");
  }

  @Test
  void aggregationNameIsCaseInsensitive() {
    assertThat(translate(request("5m", Map.of(), List.of(), "AVG"))).contains("avg_over_time");
  }

  @Test
  void rejectsAnAggregationTheMetricDoesNotPermit() {
    assertThatThrownBy(() -> translate(request("5m", Map.of(), List.of(), "sum")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_AGGREGATION"));
  }

  @Test
  void rejectsAnAggregationThatDoesNotExist() {
    assertThatThrownBy(() -> translate(request("5m", Map.of(), List.of(), "median")))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_AGGREGATION"));
  }

  @Test
  void rejectsUndeclaredFilterDimension() {
    assertThatThrownBy(
            () -> translate(request("5m", Map.of("job", "wh-backend"), List.of(), "avg")))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_DIMENSION"));
  }

  @Test
  void rejectsUndeclaredGroupByDimension() {
    assertThatThrownBy(() -> translate(request("5m", Map.of(), List.of("region"), "avg")))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_DIMENSION"));
  }

  @Test
  void rejectsStepBelowTheFloor() {
    assertThatThrownBy(() -> translate(request("1s", Map.of(), List.of(), "avg")))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void rejectsMalformedStep() {
    for (String bad : List.of("5", "5x", "", "abc", "-5m", "5m; drop")) {
      assertThatThrownBy(() -> translate(request(bad, Map.of(), List.of(), "avg")))
          .as("step %s", bad)
          .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
    }
  }

  @Test
  void rejectsRangeLongerThanTheCap() {
    MetricsQueryRequest tooLong =
        new MetricsQueryRequest(
            "wh.vehicle.battery",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z"),
            "1h",
            Map.of(),
            List.of(),
            "avg");
    assertThatThrownBy(() -> translator.translate(tooLong, BATTERY))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void rejectsTooManyPointsPerSeries() {
    // 30 days at 10s is ~259k points — under the range cap, far over the point cap.
    MetricsQueryRequest dense =
        new MetricsQueryRequest(
            "wh.vehicle.battery",
            Instant.parse("2026-08-01T00:00:00Z"),
            Instant.parse("2026-08-31T00:00:00Z"),
            "10s",
            Map.of(),
            List.of(),
            "avg");
    assertThatThrownBy(() -> translator.translate(dense, BATTERY))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void rejectsInvertedRange() {
    MetricsQueryRequest inverted =
        new MetricsQueryRequest("wh.vehicle.battery", TO, FROM, "5m", Map.of(), List.of(), "avg");
    assertThatThrownBy(() -> translator.translate(inverted, BATTERY))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void escapesQuotesInFilterValuesSoTheSelectorCannotBeBrokenOutOf() {
    String query = translate(request("5m", Map.of("vehicle_id", "V\"} or up{"), List.of(), "avg"));
    assertThat(query)
        .isEqualTo("avg(avg_over_time(wh_vehicle_battery{vehicle_id=\"V\\\"} or up{\"}[5m]))");
  }

  @Test
  void rejectsControlCharactersInFilterValues() {
    assertThatThrownBy(
            () -> translate(request("5m", Map.of("vehicle_id", "V\nup"), List.of(), "avg")))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_DIMENSION"));
  }

  @Test
  void nullFiltersAndGroupByAreTreatedAsEmpty() {
    MetricsQueryRequest sparse =
        new MetricsQueryRequest("wh.vehicle.battery", FROM, TO, "5m", null, null, "avg");
    assertThat(translator.translate(sparse, BATTERY))
        .isEqualTo("avg(avg_over_time(wh_vehicle_battery[5m]))");
  }
}
