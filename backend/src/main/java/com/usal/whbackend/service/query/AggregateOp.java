package com.usal.whbackend.service.query;

import java.util.Locale;
import java.util.Optional;

/** The accumulators a grouped query may apply. An allow-list, like {@link Operator}. */
public enum AggregateOp {
  COUNT,
  SUM,
  AVG,
  MIN,
  MAX;

  /** COUNT counts documents; every other op needs something to accumulate. */
  public boolean requiresField() {
    return this != COUNT;
  }

  /**
   * Whether this op means anything for a field of that type.
   *
   * <p>MIN and MAX are defined on instants — "when was this SKU last ordered" is a {@code max} over
   * a date. SUM and AVG are not: adding two dates produces nothing meaningful.
   */
  public boolean accepts(FieldType type) {
    return switch (this) {
      case COUNT -> true;
      case SUM, AVG -> type == FieldType.NUMBER;
      case MIN, MAX -> type == FieldType.NUMBER || type == FieldType.INSTANT;
    };
  }

  public static Optional<AggregateOp> parse(String raw) {
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
