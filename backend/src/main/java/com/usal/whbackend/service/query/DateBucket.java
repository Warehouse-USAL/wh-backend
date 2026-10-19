package com.usal.whbackend.service.query;

import java.util.Locale;
import java.util.Optional;

/**
 * Truncates an instant to a calendar bucket for grouping.
 *
 * <p>Rendered with {@code $dateToString} rather than {@code $dateTrunc}, which needs MongoDB 5.0.
 * This deployment is pinned to 4.4 — the last line that runs without AVX, because the production
 * box crashes 5.0+ with "Illegal instruction". Buckets are therefore strings, which sort
 * lexicographically in chronological order and chart directly.
 */
public enum DateBucket {
  HOUR("%Y-%m-%dT%H:00:00"),
  DAY("%Y-%m-%d"),
  MONTH("%Y-%m");

  private final String format;

  DateBucket(String format) {
    this.format = format;
  }

  public String format() {
    return format;
  }

  public static Optional<DateBucket> parse(String raw) {
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
