package com.usal.whbackend.telemetry;

/**
 * A point-in-time telemetry reading from a vehicle.
 *
 * <p>Deliberately owned by this package rather than reusing the Kafka message record: the Kafka
 * consumer depends on {@link TelemetryPort}, so depending back on {@code repository.kafka} for the
 * payload type would make the two packages mutually dependent and tie telemetry to a transport.
 */
public record VehicleSample(String vehicleId, int batteryPercent) {}
