package com.usal.whbackend.telemetry;

/** A single vehicle's current status, as seen by {@link FleetStateSource}. */
public record VehicleState(String vehicleId, String status) {}
