package com.usal.whbackend.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Locale;

public enum StockSize {
  CAJA(48000.0),
  MEDIO_PALLET(900000.0),
  PALLET(1800000.0);

  private final double volumeCm3;

  StockSize(double volumeCm3) {
    this.volumeCm3 = volumeCm3;
  }

  public double getVolumeCm3() {
    return volumeCm3;
  }

  /**
   * Lenient parser that accepts both the current names and the legacy pre-rename
   * vocabulary (PEQUENO/MEDIANO/GRANDE and the English/feminine spellings),
   * mapping them onto the current model ordered by volume. Documents written by
   * older versions and clients still sending the old values keep working instead
   * of failing with "No enum constant ... StockSize.MEDIANO".
   *
   * <p>Used both by Jackson (request bodies, via {@code @JsonCreator}) and by the
   * Mongo read converter ({@code MongoConfig}). Serialization is unchanged — the
   * canonical enum name is always written back.
   */
  @JsonCreator
  public static StockSize fromValue(String raw) {
    if (raw == null) {
      return null;
    }
    String value = raw.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "CAJA", "PEQUENO", "PEQUENA", "SMALL" -> CAJA;
      case "MEDIO_PALLET", "MEDIANO", "MEDIANA", "MEDIUM" -> MEDIO_PALLET;
      case "PALLET", "GRANDE", "LARGE" -> PALLET;
      default -> StockSize.valueOf(value);
    };
  }
}
