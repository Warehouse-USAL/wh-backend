package com.usal.whbackend.service;

import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.telemetry.FleetStateSource;
import com.usal.whbackend.telemetry.VehicleState;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Reads the fleet for the observable state gauge.
 *
 * <p>Implements a telemetry-owned interface so the dependency points inward: telemetry never
 * imports the repository or domain packages.
 *
 * <p>Called once per export interval (10s by default) and reads the whole collection. That is
 * affordable because the fleet is three rovers; if it ever grows, this is the place to add a
 * projection or a cache, not the gauge.
 */
@Component
public class FleetStateProvider implements FleetStateSource {

  private final VehicleRepository vehicleRepository;

  public FleetStateProvider(VehicleRepository vehicleRepository) {
    this.vehicleRepository = vehicleRepository;
  }

  @Override
  public List<VehicleState> currentFleet() {
    return vehicleRepository.findAll().stream()
        .map(
            v ->
                new VehicleState(
                    v.getId(), v.getStatus() == null ? "UNKNOWN" : v.getStatus().name()))
        .toList();
  }

  @Override
  public List<String> knownStates() {
    return Arrays.stream(VehicleStatus.values()).map(Enum::name).toList();
  }
}
