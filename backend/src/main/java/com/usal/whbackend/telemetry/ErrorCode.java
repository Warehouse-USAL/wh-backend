package com.usal.whbackend.telemetry;

import java.util.Locale;

/**
 * Bounded taxonomy for vehicle fault categories, carried on {@link VehicleStatusChange#category()}.
 *
 * <p>Replaces the {@link VehicleStatusChange#UNCATEGORIZED} placeholder wherever a producer
 * actually supplies a code (today: the {@code vehicle.error} Kafka message). A raw string is never
 * forwarded as-is — see {@link OtelTelemetryAdapter}'s javadoc on why an unbounded label value is a
 * cardinality hole straight into VictoriaMetrics — so an incoming code is matched against this enum
 * and anything unrecognized becomes {@link #OTHER} rather than being dropped or rejected.
 */
public enum ErrorCode {
  CONNECTION_LOST,
  BATTERY_CRITICAL,
  MECHANICAL_FAULT,
  COLLISION,
  NAVIGATION_ERROR,
  OTHER;

  public static ErrorCode fromRaw(String raw) {
    if (raw == null || raw.isBlank()) {
      return OTHER;
    }
    try {
      return ErrorCode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return OTHER;
    }
  }
}
