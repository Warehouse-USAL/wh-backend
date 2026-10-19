package com.usal.whbackend.service.metrics.restock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class RestockFormulaTest {

  // The worked example from the business document (RFC_Metricas_Calculadas.md §4.4).
  private static final RestockParams EXAMPLE = new RestockParams(0.3, 7, 60, 2, 5, 7);

  @Test
  void scenarioOne_nothingInTransit_triggersAndSuggests179() {
    RestockResult r = RestockFormula.compute(EXAMPLE, 22, 25, 140, 0);

    assertThat(r.blendedDemand()).isCloseTo(22.9, within(1e-9));
    assertThat(r.safetyStock()).isCloseTo(44, within(1e-9));
    assertThat(r.reorderPoint()).isCloseTo(158.5, within(1e-9));
    assertThat(r.targetStock()).isCloseTo(318.8, within(1e-9));
    assertThat(r.inventoryPosition()).isEqualTo(140);
    assertThat(r.shouldRestock()).isTrue();
    assertThat(r.suggestedQuantity()).isEqualTo(179);
  }

  @Test
  void scenarioTwo_fiftyInTransit_doesNotTrigger() {
    RestockResult r = RestockFormula.compute(EXAMPLE, 22, 25, 140, 50);

    // Reserved stock is NOT subtracted again: available is already net of reservations.
    assertThat(r.inventoryPosition()).isEqualTo(190);
    assertThat(r.shouldRestock()).isFalse();
    assertThat(r.suggestedQuantity()).isZero();
  }

  @Test
  void alphaZero_blendedIsLongTermDemand() {
    RestockParams p = new RestockParams(0, 7, 60, 2, 5, 7);
    assertThat(RestockFormula.compute(p, 22, 25, 0, 0).blendedDemand()).isEqualTo(22);
  }

  @Test
  void alphaOne_blendedIsRecentDemand() {
    RestockParams p = new RestockParams(1, 7, 60, 2, 5, 7);
    assertThat(RestockFormula.compute(p, 22, 25, 0, 0).blendedDemand()).isEqualTo(25);
  }

  @Test
  void noDemandAndNoStock_isNotASuggestion() {
    // 0 <= 0 satisfies the reorder rule, but there is nothing to order.
    RestockResult r = RestockFormula.compute(EXAMPLE, 0, 0, 0, 0);

    assertThat(r.reorderPoint()).isZero();
    assertThat(r.shouldRestock()).isFalse();
    assertThat(r.suggestedQuantity()).isZero();
  }

  @Test
  void exactIntegerGap_isNotRoundedUpByFloatingPointNoise() {
    // blended = 10, target = 10 × 12 + 0 = 120 → gap to position 20 is exactly 100.
    RestockParams p = new RestockParams(0.1, 7, 60, 0, 5, 7);
    RestockResult r = RestockFormula.compute(p, 10, 10, 20, 0);
    assertThat(r.suggestedQuantity()).isEqualTo(100);
  }
}
