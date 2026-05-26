package com.usal.whbackend.repository.kafka;

/**
 * Kafka message received on the {@code order.status} topic. Field names are snake_case in the wire
 * format; the injected Jackson ObjectMapper is configured with SNAKE_CASE so explicit
 * {@literal @JsonProperty} annotations are unnecessary.
 */
public record OrderStatusMessage(
    String messageType, String orderId, String vehicleId, String status, String timestamp) {}
