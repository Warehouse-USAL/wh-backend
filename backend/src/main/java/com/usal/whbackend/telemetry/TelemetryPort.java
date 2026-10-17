package com.usal.whbackend.telemetry;

/**
 * Records domain telemetry. The wrapper the rest of the codebase talks to, so no other class needs
 * to know OpenTelemetry exists.
 *
 * <p>Implementations MUST NOT throw. Telemetry is observational: losing a sample is acceptable,
 * failing the request or the Kafka message that produced it is not.
 */
public interface TelemetryPort {

  /** Records a vehicle telemetry reading. Never throws. */
  void recordVehicleSample(VehicleSample sample);

  /**
   * Records that a vehicle changed status. Never throws.
   *
   * <p>Only actual changes should be passed: the counter it feeds is the denominator of every
   * failure-rate the dashboard derives, so re-recording an unchanged status would inflate them.
   */
  void recordStatusTransition(VehicleStatusChange change);
}
