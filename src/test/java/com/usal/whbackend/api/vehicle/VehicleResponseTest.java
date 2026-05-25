package com.usal.whbackend.api.vehicle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleResponseTest {

  @Test
  void recordAccessors() {
    Instant now = Instant.now();
    VehicleResponse response =
        new VehicleResponse(
            "id-1",
            "Rover-01",
            "IDLE",
            new VehicleResponse.Position(10.5, 20.3),
            85,
            "order-1",
            now);

    assertEquals("id-1", response.id());
    assertEquals("Rover-01", response.name());
    assertEquals("IDLE", response.status());
    assertEquals(10.5, response.position().x());
    assertEquals(20.3, response.position().y());
    assertEquals(85, response.battery());
    assertEquals("order-1", response.currentOrderId());
    assertEquals(now, response.lastSeenAt());
  }
}
