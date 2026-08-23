package com.usal.whbackend.service.metrics;

import java.util.Locale;
import java.util.Optional;

/**
 * How samples are combined, in both dimensions of a range query:
 *
 * <ul>
 *   <li>{@link #overTimeFunction()} collapses the samples inside one step bucket
 *   <li>{@link #operator()} collapses the remaining series into the requested {@code group_by}
 * </ul>
 */
public enum Aggregation {
  AVG("avg_over_time", "avg"),
  MIN("min_over_time", "min"),
  MAX("max_over_time", "max"),
  SUM("sum_over_time", "sum"),
  // MetricsQL has no `last` aggregation operator, only `last_over_time`. The operator is only
  // reached when group_by is narrower than the metric's dimensions, where "the last value across
  // several series" is ill-defined anyway; max is the least surprising stand-in.
  LAST("last_over_time", "max");

  private final String overTimeFunction;
  private final String operator;

  Aggregation(String overTimeFunction, String operator) {
    this.overTimeFunction = overTimeFunction;
    this.operator = operator;
  }

  public String overTimeFunction() {
    return overTimeFunction;
  }

  public String operator() {
    return operator;
  }

  /** Case-insensitive lookup that never throws — callers turn the empty case into a 400. */
  public static Optional<Aggregation> parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
