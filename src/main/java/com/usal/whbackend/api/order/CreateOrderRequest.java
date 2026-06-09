package com.usal.whbackend.api.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CreateOrderRequest(
    List<OrderItemRequest> items,
    @JsonProperty("destination_area") String destinationArea,
    AddressRequest address) {

  public CreateOrderRequest {
    items = items == null ? null : List.copyOf(items);
  }

  public record OrderItemRequest(@JsonProperty("product_id") String productId, int quantity) {}

  public record AddressRequest(
      String street,
      String department,
      String floor,
      @JsonProperty("postal_code") String postalCode) {}
}
