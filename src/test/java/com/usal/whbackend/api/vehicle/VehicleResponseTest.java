package com.usal.whbackend.api.vehicle;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleResponseTest {

  @Test
  void recordAccessors() {
    Instant now = Instant.now();
    Instant since = now.minusSeconds(3600);
    VehicleResponse response =
        new VehicleResponse(
            "id-1",
            "Rover-01",
            "IDLE",
            new VehicleResponse.Position(10.5, 20.3),
            85,
            "order-1",
            now,
            since);

    assertEquals("id-1", response.id());
    assertEquals("Rover-01", response.name());
    assertEquals("IDLE", response.status());
    assertEquals(10.5, response.position().x());
    assertEquals(20.3, response.position().y());
    assertEquals(85, response.battery());
    assertEquals("order-1", response.currentOrderId());
    assertEquals(now, response.lastSeenAt());
    assertEquals(since, response.operationSince());
  }

  @Test
  void from_vehicleWithoutOperationSince_yieldsNullOperationSince() {
    com.usal.whbackend.domain.Vehicle vehicle = new com.usal.whbackend.domain.Vehicle();
    vehicle.setId("v-1");
    vehicle.setStatus(com.usal.whbackend.domain.VehicleStatus.OFFLINE);

    VehicleResponse r = VehicleResponse.from(vehicle);

    assertNull(r.operationSince());
  }

  @Test
  void from_vehicleWithOperationSince_mapsIt() {
    com.usal.whbackend.domain.Vehicle vehicle = new com.usal.whbackend.domain.Vehicle();
    vehicle.setId("v-1");
    vehicle.setStatus(com.usal.whbackend.domain.VehicleStatus.IDLE);
    Instant since = Instant.parse("2026-05-01T10:00:00Z");
    vehicle.setOperationSince(since);

    VehicleResponse r = VehicleResponse.from(vehicle);

    assertEquals(since, r.operationSince());
  }
}
