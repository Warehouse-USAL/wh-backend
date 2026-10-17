package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.UserRole;
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
}
