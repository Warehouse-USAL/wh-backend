package com.usal.whbackend.service;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

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
    throw new UnsupportedOperationException("not implemented");
  }

  public Vehicle registerVehicle(RegisterVehicleRequest request) {
    throw new UnsupportedOperationException("not implemented");
  }
}
