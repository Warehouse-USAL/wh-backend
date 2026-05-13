package com.usal.whbackend.api.order;

import java.util.List;

public record CreateOrderRequest(List<OrderItemRequest> items, String destinationArea) {

  public CreateOrderRequest {
    items = items == null ? null : List.copyOf(items);
  }

  public record OrderItemRequest(String productId, int quantity) {}
}
