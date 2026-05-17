package com.usal.whbackend.service;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VehicleRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VehicleService {

  private final VehicleRepository vehicleRepository;

  public VehicleService(VehicleRepository vehicleRepository) {
    this.vehicleRepository = vehicleRepository;
  }

  public List<Vehicle> getVehicles() {
    throw new UnsupportedOperationException("not implemented");
  }

  public Vehicle getVehicle(String id) {
    throw new UnsupportedOperationException("not implemented");
  }

  public Vehicle registerVehicle(RegisterVehicleRequest request) {
    throw new UnsupportedOperationException("not implemented");
  }
}
