package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleTest {

  @Test
  void gettersAndSetters() {
    Vehicle vehicle = new Vehicle();
    Instant now = Instant.now();

    vehicle.setId("id-1");
    vehicle.setName("Rover-01");
    vehicle.setStatus(VehicleStatus.IDLE);
    vehicle.setPositionX(10.5);
    vehicle.setPositionY(20.3);
    vehicle.setBattery(85);
    vehicle.setCurrentOrderId("order-1");
    vehicle.setLastSeenAt(now);

    assertEquals("id-1", vehicle.getId());
    assertEquals("Rover-01", vehicle.getName());
    assertEquals(VehicleStatus.IDLE, vehicle.getStatus());
    assertEquals(10.5, vehicle.getPositionX());
    assertEquals(20.3, vehicle.getPositionY());
    assertEquals(85, vehicle.getBattery());
    assertEquals("order-1", vehicle.getCurrentOrderId());
    assertEquals(now, vehicle.getLastSeenAt());
  }
}
