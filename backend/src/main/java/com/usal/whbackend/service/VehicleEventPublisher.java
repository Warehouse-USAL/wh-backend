package com.usal.whbackend.service;

import com.usal.whbackend.domain.Vehicle;

public interface VehicleEventPublisher {
  void broadcastVehicleUpdate(Vehicle vehicle);

  void broadcastVehicleError(String vehicleId, String errorCode, String message, String lastSeenAt);
}
