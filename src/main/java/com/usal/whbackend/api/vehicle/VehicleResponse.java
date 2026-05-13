package com.usal.whbackend.api.vehicle;

import com.usal.whbackend.domain.Vehicle;
import java.time.Instant;

public record VehicleResponse(
    String id,
    String name,
    String status,
    Position position,
    int battery,
    String currentOrderId,
    Instant lastSeenAt) {

  public record Position(double x, double y) {}

  public static VehicleResponse from(Vehicle vehicle) {
    return new VehicleResponse(
        vehicle.getId(),
        vehicle.getName(),
        vehicle.getStatus() != null ? vehicle.getStatus().name().toLowerCase() : null,
        new Position(vehicle.getPositionX(), vehicle.getPositionY()),
        vehicle.getBattery(),
        vehicle.getCurrentOrderId(),
        vehicle.getLastSeenAt());
  }
}
