package com.usal.whbackend.service.query;

import java.util.Locale;
import java.util.Optional;

/**
 * The complete set of permitted filter operators.
 *
 * <p>An allow-list, deliberately: {@code $where}, {@code $function} and {@code $expr} execute
 * server-side and are absent by construction rather than by filtering.
 */
public enum Operator {
  EQ,
  NE,
  GT,
  GTE,
  LT,
  LTE,
  IN,
  NIN,
  CONTAINS,
  EXISTS;

  public boolean isMultiValue() {
    return this == IN || this == NIN;
  }

  public static Optional<Operator> parse(String raw) {
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
