package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class IdempotencyRecordTest {

  @Test
  void testNoArgsConstructorAndSetters() {
    IdempotencyRecord record = new IdempotencyRecord();
    Instant now = Instant.now();

    record.setId("rec-1");
    record.setKey("key-123");
    record.setUserId("user-1");
    record.setOrderId("order-1");
    record.setCreatedAt(now);

    assertEquals("rec-1", record.getId());
    assertEquals("key-123", record.getKey());
    assertEquals("user-1", record.getUserId());
    assertEquals("order-1", record.getOrderId());
    assertEquals(now, record.getCreatedAt());
  }

  @Test
  void testAllArgsConstructor() {
    Instant now = Instant.now();
    IdempotencyRecord record = new IdempotencyRecord("key-456", "user-2", "order-2", now);

    assertNull(record.getId());
    assertEquals("key-456", record.getKey());
    assertEquals("user-2", record.getUserId());
    assertEquals("order-2", record.getOrderId());
    assertEquals(now, record.getCreatedAt());
  }
}
