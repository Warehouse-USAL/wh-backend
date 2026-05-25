package com.usal.whbackend.api.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateOrderRequest(
    List<OrderItemRequest> items, @JsonProperty("destination_area") String destinationArea) {

  public CreateOrderRequest {
    items = items == null ? null : List.copyOf(items);
  }

  public record OrderItemRequest(@JsonProperty("product_id") String productId, int quantity) {}
}
