package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record OrderDispatchMessage(
    @JsonProperty("message_type") String messageType,
    @JsonProperty("order_id") String orderId,
    List<Item> items,
    @JsonProperty("destination_area") String destinationArea,
    Address address,
    @JsonProperty("published_at") String publishedAt) {

  public OrderDispatchMessage {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Item(@JsonProperty("product_id") String productId, String sku, int quantity) {}

  public record Address(
      String street,
      String department,
      String floor,
      @JsonProperty("postal_code") String postalCode) {}
}
