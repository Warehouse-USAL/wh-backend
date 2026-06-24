package com.usal.whbackend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockSizeTest {

  @ParameterizedTest
  @CsvSource({
    // current names pass through
    "CAJA, CAJA",
    "MEDIO_PALLET, MEDIO_PALLET",
    "PALLET, PALLET",
    // legacy pre-rename vocabulary maps by volume
    "PEQUENO, CAJA",
    "MEDIANO, MEDIO_PALLET",
    "GRANDE, PALLET",
    // feminine / english spellings and casing/whitespace tolerance
    "PEQUENA, CAJA",
    "MEDIANA, MEDIO_PALLET",
    "small, CAJA",
    "medium, MEDIO_PALLET",
    "large, PALLET",
    "' medio_pallet ', MEDIO_PALLET"
  })
  void fromValue_mapsLegacyAndCurrentValues(String raw, StockSize expected) {
    assertThat(StockSize.fromValue(raw)).isEqualTo(expected);
  }

  @Test
  void fromValue_nullReturnsNull() {
    assertThat(StockSize.fromValue(null)).isNull();
  }

  @Test
  void fromValue_unknownStillThrows() {
    assertThatThrownBy(() -> StockSize.fromValue("NOT_A_SIZE"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
