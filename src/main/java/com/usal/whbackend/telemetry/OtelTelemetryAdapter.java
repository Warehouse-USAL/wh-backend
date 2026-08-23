package com.usal.whbackend.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class in the codebase that imports {@code io.opentelemetry.*}. Enforced by {@code
 * ArchitectureTest.openTelemetryIsConfinedToTheTelemetryPackage}.
 *
 * <p>Publishes raw fleet state and nothing derived. MTBF, MTTR and failure rates are ratios of
 * these series, computed by whoever is drawing the chart — see the design spec's division of
 * labour. Deriving them here would mean owning somebody else's definitions.
 */
public class OtelTelemetryAdapter implements TelemetryPort {

  private static final Logger log = LoggerFactory.getLogger(OtelTelemetryAdapter.class);

  /** Catalogue name. Stored by VictoriaMetrics as {@code wh_vehicle_battery}. */
  public static final String BATTERY_METRIC = "wh.vehicle.battery";

  /** Catalogue name for the fleet state-set gauge. */
  public static final String STATE_METRIC = "wh.vehicle.state";

  /** Catalogue name for the status-transition counter. */
  public static final String TRANSITIONS_METRIC = "wh.vehicle.transitions";

  static final AttributeKey<String> VEHICLE_ID = AttributeKey.stringKey("vehicle_id");
  static final AttributeKey<String> STATE = AttributeKey.stringKey("state");
  static final AttributeKey<String> FROM = AttributeKey.stringKey("from");
  static final AttributeKey<String> TO = AttributeKey.stringKey("to");
  static final AttributeKey<String> CATEGORY = AttributeKey.stringKey("category");

  private final DoubleGauge batteryGauge;
  private final LongCounter transitionCounter;

  public OtelTelemetryAdapter(Meter meter, FleetStateSource fleetStateSource) {
    this.batteryGauge =
        meter
            .gaugeBuilder(BATTERY_METRIC)
            .setUnit("%")
            .setDescription("Vehicle battery charge level, 0-100")
            .build();

    this.transitionCounter =
        meter
            .counterBuilder(TRANSITIONS_METRIC)
            .setUnit("1")
            .setDescription("Vehicle status transitions, counted once per observed change")
            .build();

    registerStateGauge(meter, fleetStateSource);
  }

  /**
   * An <em>observable</em> gauge, deliberately: it is polled once per export interval rather than
   * written when a message arrives.
   *
   * <p>A synchronous gauge only updates on message arrival, so a vehicle that stops publishing
   * keeps exporting its last value indefinitely — summing those to count active rovers would count
   * dead ones forever. Reading the whole fleet each tick means the published series set is always
   * exactly the current fleet.
   *
   * <p>The returned handle is not retained: {@code SdkMeterProvider} holds the callback for its own
   * lifetime, and the SDK bean is closed on shutdown.
   */
  private void registerStateGauge(Meter meter, FleetStateSource fleetStateSource) {
    meter
        .gaugeBuilder(STATE_METRIC)
        .setUnit("1")
        .setDescription("1 when the vehicle is in that state, 0 otherwise")
        .buildWithCallback(
            measurement -> {
              try {
                List<String> states = fleetStateSource.knownStates();
                for (VehicleState vehicle : fleetStateSource.currentFleet()) {
                  for (String state : states) {
                    measurement.record(
                        state.equals(vehicle.status()) ? 1 : 0,
                        Attributes.of(VEHICLE_ID, vehicle.vehicleId(), STATE, state));
                  }
                }
              } catch (RuntimeException e) {
                // Runs on the SDK's export thread. Throwing here would kill the export, taking
                // every other metric down with it.
                log.warn("Failed to observe fleet state: {}", e.getMessage());
              }
            });
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

  @Override
  public void recordStatusTransition(VehicleStatusChange change) {
    if (change == null || change.vehicleId() == null || change.vehicleId().isBlank()) {
      return;
    }
    try {
      transitionCounter.add(
          1,
          Attributes.builder()
              .put(VEHICLE_ID, change.vehicleId())
              .put(FROM, nullSafe(change.from()))
              .put(TO, nullSafe(change.to()))
              .put(CATEGORY, nullSafe(change.category()))
              .build());
    } catch (RuntimeException e) {
      log.warn(
          "Failed to record status transition for vehicle {}: {}",
          change.vehicleId(),
          e.getMessage());
    }
  }

  /** A null label would be dropped by the SDK, silently changing the series identity. */
  private static String nullSafe(String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value;
  }
}
