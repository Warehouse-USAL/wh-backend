package com.usal.whbackend.service.metrics.restock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import org.junit.jupiter.api.Test;

class RestockParamsTest {

  @Test
  void validated_acceptsTheDocumentExample() {
    assertThat(RestockParams.validated(0.3, 7, 60, 2.0, 5.0, 7.0))
        .isEqualTo(new RestockParams(0.3, 7, 60, 2, 5, 7));
  }

  @Test
  void validated_rejectsAMissingParamNamingIt() {
    assertThatThrownBy(() -> RestockParams.validated(0.3, 7, 60, 2.0, null, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("lead_time_days");
  }

  @Test
  void validated_rejectsAlphaOutsideZeroToOne() {
    assertThatThrownBy(() -> RestockParams.validated(1.5, 7, 60, 2.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("alpha");
  }

  @Test
  void validated_rejectsALongWindowNotLongerThanTheRecentOne() {
    assertThatThrownBy(() -> RestockParams.validated(0.3, 7, 7, 2.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("long_days");
  }

  @Test
  void validated_rejectsWindowsBeyondAYear() {
    assertThatThrownBy(() -> RestockParams.validated(0.3, 7, 366, 2.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("long_days");
    assertThatThrownBy(() -> RestockParams.validated(0.3, 0, 60, 2.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("recent_days");
  }

  @Test
  void validated_rejectsNegativeDays() {
    assertThatThrownBy(() -> RestockParams.validated(0.3, 7, 60, -1.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("safety_days");
    assertThatThrownBy(() -> RestockParams.validated(0.3, 7, 60, 2.0, 5.0, -7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("coverage_days");
  }

  @Test
  void validated_rejectsNonFiniteNumbers() {
    assertThatThrownBy(() -> RestockParams.validated(Double.NaN, 7, 60, 2.0, 5.0, 7.0))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("alpha");
  }
}
