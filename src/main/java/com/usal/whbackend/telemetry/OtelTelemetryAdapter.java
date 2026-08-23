package com.usal.whbackend.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class in the codebase that imports {@code io.opentelemetry.*}. Enforced by {@code
 * ArchitectureTest.openTelemetryIsConfinedToTheTelemetryPackage}.
 */
public class OtelTelemetryAdapter implements TelemetryPort {

  private static final Logger log = LoggerFactory.getLogger(OtelTelemetryAdapter.class);

  /** Catalogue name. Stored by VictoriaMetrics as {@code wh_vehicle_battery}. */
  public static final String BATTERY_METRIC = "wh.vehicle.battery";

  static final AttributeKey<String> VEHICLE_ID = AttributeKey.stringKey("vehicle_id");

  private final DoubleGauge batteryGauge;

  public OtelTelemetryAdapter(Meter meter) {
    this.batteryGauge =
        meter
            .gaugeBuilder(BATTERY_METRIC)
            .setUnit("%")
            .setDescription("Vehicle battery charge level, 0-100")
            .build();
  }

  @Override
  public void recordVehicleSample(VehicleSample sample) {
    if (sample == null || sample.vehicleId() == null || sample.vehicleId().isBlank()) {
      return;
    }
    try {
      batteryGauge.set(sample.batteryPercent(), Attributes.of(VEHICLE_ID, sample.vehicleId()));
    } catch (RuntimeException e) {
      // Telemetry is observational — never propagate. See TelemetryPort's contract.
      log.warn(
          "Failed to record battery telemetry for vehicle {}: {}",
          sample.vehicleId(),
          e.getMessage());
    }
  }
}
