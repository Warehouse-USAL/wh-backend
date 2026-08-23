package com.usal.whbackend.service.metrics;

import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parses the {@code step} of a range query, e.g. {@code 30s}, {@code 5m}, {@code 1h}, {@code 7d}.
 *
 * <p>Strict by design: the parsed text is interpolated into a MetricsQL range selector, so anything
 * outside this grammar must be rejected rather than passed through.
 */
public final class StepDuration {

  private static final Pattern GRAMMAR = Pattern.compile("^(\\d{1,6})([smhd])$");

  private StepDuration() {}

  public static Optional<Duration> parse(String raw) {
    if (raw == null) {
      return Optional.empty();
    }
    var matcher = GRAMMAR.matcher(raw.trim());
    if (!matcher.matches()) {
      return Optional.empty();
    }
    long amount = Long.parseLong(matcher.group(1));
    if (amount == 0) {
      return Optional.empty();
    }
    return Optional.of(
        switch (matcher.group(2)) {
          case "s" -> Duration.ofSeconds(amount);
          case "m" -> Duration.ofMinutes(amount);
          case "h" -> Duration.ofHours(amount);
          default -> Duration.ofDays(amount);
        });
  }
}
