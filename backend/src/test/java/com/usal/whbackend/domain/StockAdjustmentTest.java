package com.usal.whbackend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StockAdjustmentTest {

  @Test
  void cancelledOrderIds_areDefensivelyCopied() {
    List<String> ids = new ArrayList<>(List.of("o1"));

    StockAdjustment a =
        new StockAdjustment(null, "p", "x", StockAdjustmentType.DAMAGE, null, 2, 1, "u", null, ids);
    ids.add("o2");

    assertThat(a.cancelledOrderIds()).containsExactly("o1");
    assertThatThrownBy(() -> a.cancelledOrderIds().add("o3"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void missingCancelledOrderIds_becomeEmpty() {
    StockAdjustment a =
        new StockAdjustment(
            null, "p", null, StockAdjustmentType.MANUAL_INCREASE, null, 1, 2, "u", null, null);

    assertThat(a.cancelledOrderIds()).isEmpty();
  }
}
