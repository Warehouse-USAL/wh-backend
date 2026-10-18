package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MetricDescriptorTest {

  private static MetricDescriptor descriptor(MetricType type, List<Aggregation> aggregations) {
    return new MetricDescriptor(
        "wh.test",
        "wh_test",
        "Test",
        "1",
        type,
        List.of("vehicle_id"),
        aggregations,
        Set.of(UserRole.DASHBOARD));
  }

  @Test
  void aCounterMayOnlyOfferDeltaFunctions() {
    assertThatCode(() -> descriptor(MetricType.COUNTER, List.of(Aggregation.RATE)))
        .doesNotThrowAnyException();

    // sum_over_time of a counter adds up its cumulative totals: a number, but a meaningless one,
    // and a dashboard would plot it without complaint. Caught at startup rather than never.
    assertThatThrownBy(() -> descriptor(MetricType.COUNTER, List.of(Aggregation.SUM)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COUNTER");
  }

  @Test
  void aGaugeMayNotOfferDeltaFunctions() {
    assertThatCode(() -> descriptor(MetricType.GAUGE, List.of(Aggregation.AVG, Aggregation.COUNT)))
        .doesNotThrowAnyException();

    assertThatThrownBy(() -> descriptor(MetricType.GAUGE, List.of(Aggregation.INCREASE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GAUGE");
  }

  private static MetricDescriptor withBuckets(MetricType type, String buckets) {
    return new MetricDescriptor(
        "wh.test",
        "wh_test",
        "Test",
        "1",
        type,
        List.of(),
        type == MetricType.HISTOGRAM ? List.of(Aggregation.P95) : List.of(Aggregation.LAST),
        Set.of(UserRole.SUPERADMIN),
        buckets);
  }

  @Test
  void onlyAHistogramMayNameItsBucketSeries() {
    assertThat(withBuckets(MetricType.HISTOGRAM, "wh_test_raw").bucketSeriesName())
        .isEqualTo("wh_test_raw");
    assertThat(withBuckets(MetricType.HISTOGRAM, null).bucketSeriesName())
        .isEqualTo("wh_test_bucket");
    assertThat(withBuckets(MetricType.GAUGE, null).bucketSeriesName()).isNull();
    assertThatThrownBy(() -> withBuckets(MetricType.GAUGE, "wh_test_raw"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("GAUGE");
  }
}
