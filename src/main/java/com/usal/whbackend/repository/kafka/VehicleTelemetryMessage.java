package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VehicleTelemetryMessage(
    @JsonProperty("message_type") String messageType,
    @JsonProperty("vehicle_id") String vehicleId,
    Position position,
    int battery,
    String status,
    String timestamp) {

  public record Position(double x, double y) {}
}
