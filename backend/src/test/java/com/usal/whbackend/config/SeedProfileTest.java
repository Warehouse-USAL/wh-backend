package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SeedProfileTest {

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   ", "full", "FULL"})
  void blankOrFullMeansFull(String raw) {
    assertThat(SeedProfile.parse(raw)).isEqualTo(SeedProfile.FULL);
  }

  @ParameterizedTest
  @ValueSource(strings = {"demo", " Demo ", "DEMO"})
  void demoIsAcceptedWhateverItsCaseAndSpacing(String raw) {
    assertThat(SeedProfile.parse(raw)).isEqualTo(SeedProfile.DEMO);
  }

  @Test
  void anUnknownValueFailsNamingTheSettingAndTheChoices() {
    assertThatThrownBy(() -> SeedProfile.parse("dmeo"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEED_PROFILE")
        .hasMessageContaining("dmeo")
        .hasMessageContaining("full")
        .hasMessageContaining("demo");
  }
}
