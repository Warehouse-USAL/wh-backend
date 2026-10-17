package com.usal.whbackend.service.metrics.restock;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;

/**
 * The business decisions behind a restock suggestion. None of these are fixed by the method — each
 * team sends its own on every request (RFC_Metricas_Calculadas.md §6.1).
 *
 * @param alpha weight of recent demand against long-term demand, 0–1
 * @param recentDays length of the recent window
 * @param longDays length of the long window; it contains the recent one, which is excluded from the
 *     long-term average so no day counts twice
 * @param safetyDays size of the safety cushion, in days of long-term demand
 * @param leadTimeDays days a restock takes to arrive
 * @param coverageDays days of stock to hold after restocking
 */
public record RestockParams(
    double alpha,
    int recentDays,
    int longDays,
    double safetyDays,
    double leadTimeDays,
    double coverageDays) {

  /** A year: the furthest back a demand window may reach. */
  public static final int MAX_WINDOW_DAYS = 365;

  /**
   * Builds params from a request, where every field is required. Each failure names the offending
   * param in its wire (snake_case) spelling, so a team can fix its request without reading code.
   */
  public static RestockParams validated(
      Double alpha,
      Integer recentDays,
      Integer longDays,
      Double safetyDays,
      Double leadTimeDays,
      Double coverageDays) {
    double a = finite("alpha", alpha);
    if (a < 0 || a > 1) {
      throw invalid("alpha debe estar entre 0 y 1");
    }
    int recent = required("recent_days", recentDays);
    if (recent < 1 || recent >= MAX_WINDOW_DAYS) {
      throw invalid("recent_days debe estar entre 1 y " + (MAX_WINDOW_DAYS - 1));
    }
    int longWindow = required("long_days", longDays);
    if (longWindow <= recent || longWindow > MAX_WINDOW_DAYS) {
      throw invalid("long_days debe ser mayor que recent_days y a lo sumo " + MAX_WINDOW_DAYS);
    }
    return new RestockParams(
        a,
        recent,
        longWindow,
        nonNegative("safety_days", safetyDays),
        nonNegative("lead_time_days", leadTimeDays),
        nonNegative("coverage_days", coverageDays));
  }

  private static <T> T required(String name, T value) {
    if (value == null) {
      throw invalid(name + " es obligatorio");
    }
    return value;
  }

  private static double finite(String name, Double value) {
    double v = required(name, value);
    if (!Double.isFinite(v)) {
      throw invalid(name + " debe ser un número finito");
    }
    return v;
  }

  private static double nonNegative(String name, Double value) {
    double v = finite(name, value);
    if (v < 0) {
      throw invalid(name + " debe ser mayor o igual a 0");
    }
    return v;
  }

  private static InvalidMetricParamsException invalid(String message) {
    return new InvalidMetricParamsException(message);
  }
}
