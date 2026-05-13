package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderCancelMessage(
    @JsonProperty("message_type") String messageType,
    @JsonProperty("order_id") String orderId,
    String reason,
    @JsonProperty("published_at") String publishedAt) {}
