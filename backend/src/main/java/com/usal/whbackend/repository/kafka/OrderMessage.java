package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Payload of the {@code orders} topic: an order and the places to visit, nothing else. {@code
 * status} is {@code pending} when the order is created and {@code cancelled} when it is cancelled;
 * progress comes back over HTTP, never on this topic.
 */
public record OrderMessage(
    @JsonProperty("order_id") String orderId,
    @JsonProperty("status") String status,
    @JsonProperty("stops") List<String> stops,
    @JsonProperty("destination") String destination,
    @JsonProperty("published_at") String publishedAt) {

  public OrderMessage {
    stops = stops == null ? List.of() : List.copyOf(stops);
  }
}
