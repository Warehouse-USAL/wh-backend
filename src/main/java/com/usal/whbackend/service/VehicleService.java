package com.usal.whbackend.service;

import com.usal.whbackend.api.internal.vehicle.PatchVehicleRequest;
import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VehicleService {

  private final VehicleRepository vehicleRepository;
  private final VehicleEventPublisher vehicleEventPublisher;

  public VehicleService(
      VehicleRepository vehicleRepository, VehicleEventPublisher vehicleEventPublisher) {
    this.vehicleRepository = vehicleRepository;
    this.vehicleEventPublisher = vehicleEventPublisher;
  }

  public Page<Vehicle> getVehicles(Pageable pageable) {
    return vehicleRepository.findAll(pageable);
  }

  public Vehicle getVehicle(String id) {
    return vehicleRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND"));
  }

  public Vehicle registerVehicle(RegisterVehicleRequest request) {
    Vehicle vehicle = new Vehicle();
    vehicle.setName(request.name());
    vehicle.setStatus(VehicleStatus.OFFLINE);
    return vehicleRepository.save(vehicle);
  }

  public Vehicle updateVehicle(String id, PatchVehicleRequest request) {
    Vehicle vehicle = getVehicle(id);
    if (request.status() != null) {
      vehicle.setStatus(VehicleStatus.valueOf(request.status().toUpperCase()));
    }
    if (request.positionX() != null) {
      vehicle.setPositionX(request.positionX());
    }
    if (request.positionY() != null) {
      vehicle.setPositionY(request.positionY());
    }
    if (request.battery() != null) {
      vehicle.setBattery(request.battery());
    }
    if (request.currentOrderId() != null) {
      vehicle.setCurrentOrderId(request.currentOrderId());
    }
    if (request.lastSeenAt() != null) {
      vehicle.setLastSeenAt(request.lastSeenAt());
    }
    Vehicle saved = vehicleRepository.save(vehicle);
    vehicleEventPublisher.broadcastVehicleUpdate(saved);
    return saved;
  }
}
