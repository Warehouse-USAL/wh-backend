package com.usal.whbackend.service.metrics.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ConversionParamsTest {

  private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");

  @Test
  void acceptsAValidRangeAndDefaultsTheZoneToUtc() {
    ConversionParams p = ConversionParams.validated(FROM, TO, "Week", null);

    assertThat(p.granularity()).isEqualTo(Granularity.WEEK);
    assertThat(p.timeZone()).isEqualTo("UTC");
  }

  @Test
  void rejectsMissingOrInvertedOrOversizedRanges() {
    assertThatThrownBy(() -> ConversionParams.validated(null, TO, "day", null))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("from");
    assertThatThrownBy(() -> ConversionParams.validated(FROM, null, "day", null))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("to");
    assertThatThrownBy(() -> ConversionParams.validated(TO, FROM, "day", null))
        .isInstanceOf(InvalidMetricParamsException.class);
    assertThatThrownBy(() -> ConversionParams.validated(FROM, FROM, "day", null))
        .isInstanceOf(InvalidMetricParamsException.class);
    assertThatThrownBy(
            () -> ConversionParams.validated(FROM, FROM.plusSeconds(732L * 86_400), "day", null))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("731");
  }

  @Test
  void acceptsTheTwoYearWindow() {
    assertThat(ConversionParams.validated(FROM, FROM.plusSeconds(731L * 86_400), "day", null))
        .isNotNull();
  }

  @Test
  void rejectsAnUnknownGranularityOrZone() {
    assertThatThrownBy(() -> ConversionParams.validated(FROM, TO, "hour", null))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("granularity");
    assertThatThrownBy(() -> ConversionParams.validated(FROM, TO, null, null))
        .isInstanceOf(InvalidMetricParamsException.class);
    assertThatThrownBy(() -> ConversionParams.validated(FROM, TO, "day", "Mars/Olympus"))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("time_zone");
  }
}
