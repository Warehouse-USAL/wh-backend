package com.usal.whbackend.config;

import java.util.Locale;

/**
 * Which dataset {@code SEED_DEMO=true} writes, chosen with {@code SEED_PROFILE}.
 *
 * <p>{@link #FULL} is the 20-product catalogue; {@link #DEMO} is the three products physically on
 * the shelves (see {@link ShelfData}). Unrelated to the Spring profiles.
 */
public enum SeedProfile {
  FULL,
  DEMO;

  /** Blank means {@link #FULL}; anything unrecognised is a configuration error. */
  public static SeedProfile parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return FULL;
    }
    return switch (raw.trim().toLowerCase(Locale.ROOT)) {
      case "full" -> FULL;
      case "demo" -> DEMO;
      default ->
          throw new IllegalStateException(
              "SEED_PROFILE=" + raw.trim() + " is not a seed profile: use full or demo.");
    };
  }
}
