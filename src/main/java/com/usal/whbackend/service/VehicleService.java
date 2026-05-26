package com.usal.whbackend.service;

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

  public VehicleService(VehicleRepository vehicleRepository) {
    this.vehicleRepository = vehicleRepository;
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
    vehicle.setId(request.id());
    vehicle.setName(request.name());
    vehicle.setStatus(VehicleStatus.OFFLINE);
    return vehicleRepository.save(vehicle);
  }
}
