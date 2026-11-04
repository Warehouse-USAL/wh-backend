package com.usal.whbackend.api.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.usal.whbackend.domain.OrderPriority;
import java.util.List;

public record CreateOrderRequest(
    List<OrderItemRequest> items,
    @JsonProperty("destination_area") String destinationArea,
    AddressRequest address,
    OrderPriority priority) {

  public CreateOrderRequest {
    items = items == null ? null : List.copyOf(items);
  }

  /** Priority is optional — {@link com.usal.whbackend.service.OrderService} defaults it. */
  public CreateOrderRequest(
      List<OrderItemRequest> items, String destinationArea, AddressRequest address) {
    this(items, destinationArea, address, null);
  }

  /**
   * One requested line.
   *
   * @param fromRecommendation optional; {@code true} when the client picked this product from a
   *     suggestion of the suggestions service. Absent or {@code null} means {@code false}, so
   *     clients that do not know the field keep working unchanged.
   */
  public record OrderItemRequest(
      @JsonProperty("product_id") String productId,
      int quantity,
      @JsonProperty("from_recommendation") Boolean fromRecommendation) {

    public OrderItemRequest(String productId, int quantity) {
      this(productId, quantity, null);
    }

    public boolean recommended() {
      return Boolean.TRUE.equals(fromRecommendation);
    }
  }

  public record AddressRequest(
      String street,
      String department,
      String floor,
      @JsonProperty("postal_code") String postalCode) {}
}
