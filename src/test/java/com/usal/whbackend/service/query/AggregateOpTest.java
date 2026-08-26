package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AggregateOpTest {

  @Test
  void onlyCountWorksWithoutAField() {
    assertThat(AggregateOp.COUNT.requiresField()).isFalse();
    for (AggregateOp op : AggregateOp.values()) {
      if (op != AggregateOp.COUNT) {
        assertThat(op.requiresField()).describedAs("%s", op).isTrue();
      }
    }
  }

  @Test
  void countAcceptsAnyType() {
    for (FieldType type : FieldType.values()) {
      assertThat(AggregateOp.COUNT.accepts(type)).describedAs("%s", type).isTrue();
    }
  }

  @Test
  void sumAndAvgAreNumericOnly() {
    for (AggregateOp op : List.of(AggregateOp.SUM, AggregateOp.AVG)) {
      assertThat(op.accepts(FieldType.NUMBER)).isTrue();
      assertThat(op.accepts(FieldType.INSTANT)).describedAs("%s on a date", op).isFalse();
      assertThat(op.accepts(FieldType.STRING)).isFalse();
      assertThat(op.accepts(FieldType.ENUM)).isFalse();
      assertThat(op.accepts(FieldType.BOOLEAN)).isFalse();
    }
  }

  @Test
  void minAndMaxAlsoAcceptInstantsBecauseLastOrderedIsARealQuestion() {
    for (AggregateOp op : List.of(AggregateOp.MIN, AggregateOp.MAX)) {
      assertThat(op.accepts(FieldType.NUMBER)).isTrue();
      assertThat(op.accepts(FieldType.INSTANT)).isTrue();
      assertThat(op.accepts(FieldType.STRING)).isFalse();
    }
  }

  @Test
  void parsesCaseInsensitivelyAndNeverThrows() {
    assertThat(AggregateOp.parse("sum")).contains(AggregateOp.SUM);
    assertThat(AggregateOp.parse("  MAX ")).contains(AggregateOp.MAX);
    assertThat(AggregateOp.parse("median")).isEmpty();
    assertThat(AggregateOp.parse("")).isEmpty();
    assertThat(AggregateOp.parse("   ")).isEmpty();
    assertThat(AggregateOp.parse(null)).isEmpty();
  }
}
