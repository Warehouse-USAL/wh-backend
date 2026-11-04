package com.usal.whbackend.service.metrics.conversion;

import com.fasterxml.jackson.annotation.JsonValue;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Optional;

/** The calendar bucket a time-bucketed computed metric groups by. */
public enum Granularity {
  DAY,
  /** ISO weeks, starting on Monday. */
  WEEK,
  MONTH;

  /** The first day of the bucket {@code date} falls in. */
  public LocalDate start(LocalDate date) {
    return switch (this) {
      case DAY -> date;
      case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
      case MONTH -> date.withDayOfMonth(1);
    };
  }

  /** The first day of the bucket after the one that starts at {@code start}. */
  public LocalDate next(LocalDate start) {
    return switch (this) {
      case DAY -> start.plusDays(1);
      case WEEK -> start.plusWeeks(1);
      case MONTH -> start.plusMonths(1);
    };
  }

  /** Wire name: lower case, as the catalogue documents it. */
  @JsonValue
  public String wire() {
    return name().toLowerCase(Locale.ROOT);
  }

  public static Optional<Granularity> parse(String value) {
    if (value == null) {
      return Optional.empty();
    }
    String upper = value.trim().toUpperCase(Locale.ROOT);
    for (Granularity g : values()) {
      if (g.name().equals(upper)) {
        return Optional.of(g);
      }
    }
    return Optional.empty();
  }
}
