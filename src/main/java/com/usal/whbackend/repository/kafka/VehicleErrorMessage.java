package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

public record VehicleErrorMessage(
    @JsonProperty("message_type") String messageType,
    @JsonProperty("vehicle_id") String vehicleId,
    @JsonProperty("error_code") String errorCode,
    String message,
    String timestamp) {}
