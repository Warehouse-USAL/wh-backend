package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderStatusMessage(
    @JsonProperty("message_type") String messageType,
    @JsonProperty("order_id") String orderId,
    @JsonProperty("vehicle_id") String vehicleId,
    String status,
    String timestamp) {}
