package com.usal.whbackend.telemetry;

/** Discards every sample. Used when {@code telemetry.enabled=false}, and throughout the tests. */
public class NoOpTelemetryAdapter implements TelemetryPort {

  @Override
  public void recordVehicleSample(VehicleSample sample) {
    // intentionally empty
  }

  @Override
  public void recordStatusTransition(VehicleStatusChange change) {
    // intentionally empty
  }
}
