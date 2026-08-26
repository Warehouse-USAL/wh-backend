package com.usal.whbackend.service.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AggregationTest {

  @Test
  void counterDeltasCombineAcrossSeriesWithSum() {
    // Averaging or maxing deltas from several rovers would answer a different question than
    // "how many failures were there", which is the only one a Pareto or an MTBF needs.
    assertThat(Aggregation.INCREASE.operator()).isEqualTo("sum");
    assertThat(Aggregation.RATE.operator()).isEqualTo("sum");
    assertThat(Aggregation.INCREASE.isCounterDelta()).isTrue();
    assertThat(Aggregation.RATE.isCounterDelta()).isTrue();
  }

  @Test
  void countAveragesWithinTheBucketThenSumsAcrossSeries() {
    // The pairing no other value provides. On a 1/0 state gauge this is "how many rovers were in
    // this state at once"; SUM would use sum_over_time and scale the answer by the sample count.
    assertThat(Aggregation.COUNT.overTimeFunction()).isEqualTo("avg_over_time");
    assertThat(Aggregation.COUNT.operator()).isEqualTo("sum");
    assertThat(Aggregation.COUNT.isCounterDelta()).isFalse();
    assertThat(Aggregation.SUM.overTimeFunction()).isEqualTo("sum_over_time");
  }

  @Test
  void parsesCaseInsensitivelyAndNeverThrows() {
    assertThat(Aggregation.parse("increase")).contains(Aggregation.INCREASE);
    assertThat(Aggregation.parse("  RaTe ")).contains(Aggregation.RATE);
    assertThat(Aggregation.parse("median")).isEmpty();
    assertThat(Aggregation.parse(null)).isEmpty();
  }
}
