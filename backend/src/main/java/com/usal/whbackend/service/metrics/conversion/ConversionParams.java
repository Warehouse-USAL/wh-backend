package com.usal.whbackend.service.metrics.conversion;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Validated params of {@code POST /metrics/suggestion-conversion}. {@code from} is inclusive and
 * {@code to} exclusive; the range is capped at two years, the span of the demo history.
 */
public record ConversionParams(Instant from, Instant to, Granularity granularity, String timeZone) {

  public static final Duration MAX_RANGE = Duration.ofDays(731);

  /** Used when the caller does not name a zone. */
  public static final String DEFAULT_TIME_ZONE = "UTC";

  public static ConversionParams validated(
      Instant from, Instant to, String granularity, String timeZone) {
    if (from == null) {
      throw new InvalidMetricParamsException("from es obligatorio");
    }
    if (to == null) {
      throw new InvalidMetricParamsException("to es obligatorio");
    }
    if (!to.isAfter(from)) {
      throw new InvalidMetricParamsException("to debe ser posterior a from");
    }
    if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
      throw new InvalidMetricParamsException("el rango no puede superar 731 días");
    }
    Granularity g =
        Granularity.parse(granularity)
            .orElseThrow(
                () ->
                    new InvalidMetricParamsException(
                        "granularity inválida. Valores aceptados: day, week, month"));
    String zone = timeZone == null || timeZone.isBlank() ? DEFAULT_TIME_ZONE : timeZone.trim();
    try {
      ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new InvalidMetricParamsException("time_zone inválida: " + zone);
    }
    return new ConversionParams(from, to, g, zone);
  }

  public ZoneId zone() {
    return ZoneId.of(timeZone);
  }
}
