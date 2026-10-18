package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.domain.UserRole;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class MetricsQueryTranslatorHistogramTest {

  private final MetricsQueryTranslator translator = new MetricsQueryTranslator();

  private static final List<Aggregation> HISTOGRAM_AGGREGATIONS =
      List.of(
          Aggregation.P50,
          Aggregation.P95,
          Aggregation.P99,
          Aggregation.RATE,
          Aggregation.INCREASE);

  private static final MetricDescriptor DURATION =
      descriptor(MetricType.HISTOGRAM, HISTOGRAM_AGGREGATIONS);

  private static final Instant FROM = Instant.parse("2026-10-18T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-18T01:00:00Z");

  private static MetricDescriptor descriptor(MetricType type, List<Aggregation> aggregations) {
    return new MetricDescriptor(
        "http.server.duration",
        "http_server_duration",
        "HTTP server duration",
        "ms",
        type,
        List.of("route", "method", "status_class"),
        aggregations,
        Set.of(UserRole.SUPERADMIN));
  }

  private String translate(Map<String, String> filters, List<String> groupBy, String agg) {
    return translator.translate(
        new MetricsQueryRequest("http.server.duration", FROM, TO, "30s", filters, groupBy, agg),
        DURATION);
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  @Test
  void quantileReadsTheBucketsKeepingLeInTheGrouping() {
    assertThat(translate(Map.of("method", "GET"), List.of("route"), "p95"))
        .isEqualTo(
            "histogram_quantile(0.95, sum by (le, route) "
                + "(rate(http_server_duration_bucket{method=\"GET\"}[30s])))");
  }

  @Test
  void eachQuantileRendersItsOwnFraction() {
    assertThat(translate(Map.of(), List.of(), "p50"))
        .isEqualTo("histogram_quantile(0.5, sum by (le) (rate(http_server_duration_bucket[30s])))");
    assertThat(translate(Map.of(), List.of("route", "method"), "P99"))
        .isEqualTo(
            "histogram_quantile(0.99, sum by (le, route, method) "
                + "(rate(http_server_duration_bucket[30s])))");
  }

  @Test
  void rateAndIncreaseReadTheCountSeries() {
    assertThat(translate(Map.of("method", "GET"), List.of("route"), "rate"))
        .isEqualTo("sum by (route) (rate(http_server_duration_count{method=\"GET\"}[30s]))");
    assertThat(translate(Map.of(), List.of(), "increase"))
        .isEqualTo("sum(increase(http_server_duration_count[30s]))");
  }

  @Test
  void undeclaredTagsAreStillRejected() {
    assertThatThrownBy(() -> translate(Map.of("le", "100"), List.of(), "p95"))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_DIMENSION"));
    assertThatThrownBy(() -> translate(Map.of(), List.of("path"), "rate"))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_DIMENSION"));
  }

  @Test
  void aHistogramDescriptorMayOnlyOfferQuantilesAndCountDeltas() {
    assertThatCode(() -> descriptor(MetricType.HISTOGRAM, HISTOGRAM_AGGREGATIONS))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> descriptor(MetricType.HISTOGRAM, List.of(Aggregation.AVG)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HISTOGRAM");
  }

  @Test
  void quantilesAreRejectedOnGaugesAndCounters() {
    assertThatThrownBy(() -> descriptor(MetricType.GAUGE, List.of(Aggregation.P95)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GAUGE");
    assertThatThrownBy(() -> descriptor(MetricType.COUNTER, List.of(Aggregation.P50)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COUNTER");
  }
}
