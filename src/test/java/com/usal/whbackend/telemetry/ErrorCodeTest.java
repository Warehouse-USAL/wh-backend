package com.usal.whbackend.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ErrorCodeTest {

  @Test
  void fromRaw_exactMatch_returnsTheCode() {
    assertEquals(ErrorCode.CONNECTION_LOST, ErrorCode.fromRaw("CONNECTION_LOST"));
  }

  @Test
  void fromRaw_isCaseInsensitiveAndTrimmed() {
    assertEquals(ErrorCode.BATTERY_CRITICAL, ErrorCode.fromRaw("  battery_critical  "));
  }

  @Test
  void fromRaw_unrecognizedCode_fallsBackToOther() {
    assertEquals(ErrorCode.OTHER, ErrorCode.fromRaw("SOMETHING_NEW"));
  }

  @Test
  void fromRaw_null_fallsBackToOther() {
    assertEquals(ErrorCode.OTHER, ErrorCode.fromRaw(null));
  }

  @Test
  void fromRaw_blank_fallsBackToOther() {
    assertEquals(ErrorCode.OTHER, ErrorCode.fromRaw("   "));
  }
}
