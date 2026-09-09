package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DateBucketTest {

  @Test
  void formatsSortChronologicallyAsPlainStrings() {
    // $dateToString is used instead of $dateTrunc, which needs MongoDB 5.0 — this deployment is
    // pinned to 4.4. The formats must therefore sort lexicographically in time order.
    assertThat(DateBucket.HOUR.format()).isEqualTo("%Y-%m-%dT%H:00:00");
    assertThat(DateBucket.DAY.format()).isEqualTo("%Y-%m-%d");
    assertThat(DateBucket.MONTH.format()).isEqualTo("%Y-%m");

    assertThat("2026-08-01T09:00:00").isLessThan("2026-08-01T10:00:00");
    assertThat("2026-08-09").isLessThan("2026-08-10");
    assertThat("2026-08").isLessThan("2026-09");
  }

  @Test
  void parsesCaseInsensitivelyAndNeverThrows() {
    assertThat(DateBucket.parse("day")).contains(DateBucket.DAY);
    assertThat(DateBucket.parse("  HOUR ")).contains(DateBucket.HOUR);
    assertThat(DateBucket.parse("week")).isEmpty();
    assertThat(DateBucket.parse("")).isEmpty();
    assertThat(DateBucket.parse("   ")).isEmpty();
    assertThat(DateBucket.parse(null)).isEmpty();
  }
}
