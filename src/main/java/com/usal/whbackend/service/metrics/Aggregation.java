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
  AVG("avg_over_time", "avg", false),
  MIN("min_over_time", "min", false),
  MAX("max_over_time", "max", false),
  SUM("sum_over_time", "sum", false),
  // MetricsQL has no `last` aggregation operator, only `last_over_time`. The operator is only
  // reached when group_by is narrower than the metric's dimensions, where "the last value across
  // several series" is ill-defined anyway; max is the least surprising stand-in.
  LAST("last_over_time", "max", false),

  // Averages within the bucket, then sums ACROSS series — the one pairing the others cannot
  // express. On a 1/0 state-set gauge this is the number of entities in that state: each series
  // contributes the fraction of the bucket it spent there, and the sum is how many were there at
  // once. SUM cannot stand in, because sum_over_time would add every sample in the bucket and so
  // scale the answer by the sample count.
  COUNT("avg_over_time", "sum", false),

  // Counter deltas. A counter only ever climbs, so every *_over_time function above is
  // meaningless on one — sum_over_time of a counter sums its cumulative totals. These two read
  // the change across the bucket instead, and sum is the only correct way to combine deltas
  // from several series.
  RATE("rate", "sum", true),
  INCREASE("increase", "sum", true);

  private final String overTimeFunction;
  private final String operator;
  private final boolean counterDelta;

  Aggregation(String overTimeFunction, String operator, boolean counterDelta) {
    this.overTimeFunction = overTimeFunction;
    this.operator = operator;
    this.counterDelta = counterDelta;
  }

  /** The windowing function applied inside one step bucket. */
  public String overTimeFunction() {
    return overTimeFunction;
  }

  public String operator() {
    return operator;
  }

  /** True for functions that read a counter's change rather than its value. */
  public boolean isCounterDelta() {
    return counterDelta;
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
