package com.usal.whbackend.telemetry;

/**
 * One observed change of a vehicle's status.
 *
 * <p>Owned by this package rather than by {@code repository.kafka} for the same reason as {@link
 * VehicleSample}: the consumer depends on the telemetry wrapper, so the wrapper must not depend
 * back on the consumer's message types.
 *
 * <p>{@code from} and {@code to} are plain strings, not the domain enum, so the telemetry package
 * stays free of domain imports and the label values are whatever the caller decided to publish.
 */
public record VehicleStatusChange(String vehicleId, String from, String to, String category) {

  /**
   * The placeholder category every transition carries today.
   *
   * <p>A Pareto of failures needs a taxonomy the domain does not yet have — {@code VehicleStatus}
   * has a single opaque {@code ERROR}. Publishing the label now, with one value, means adding real
   * categories later is a producer change rather than a schema change to every stored series.
   *
   * <p>When real categories arrive they MUST be validated against a bounded enum here. Free text
   * forwarded from a producer is an unbounded-cardinality hole straight into VictoriaMetrics.
   */
  public static final String UNCATEGORIZED = "UNCATEGORIZED";
}
