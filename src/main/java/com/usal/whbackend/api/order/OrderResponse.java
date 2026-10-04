package com.usal.whbackend.api.order;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderPriority;
import com.usal.whbackend.domain.OrderStatus;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
    String id,
    OrderStatus status,
    String requestedByUserId,
    List<OrderItemResponse> items,
    String destinationArea,
    String assignedVehicleId,
    AddressResponse address,
    Timestamps timestamps,
    String cancelReason,
    OrderPriority priority,
    Price total) {

  public OrderResponse {
    items = items == null ? null : List.copyOf(items);
  }

  public OrderResponse(
      String id,
      OrderStatus status,
      String requestedByUserId,
      List<OrderItemResponse> items,
      String destinationArea,
      String assignedVehicleId,
      AddressResponse address,
      Timestamps timestamps,
      String cancelReason,
      OrderPriority priority) {
    this(
        id,
        status,
        requestedByUserId,
        items,
        destinationArea,
        assignedVehicleId,
        address,
        timestamps,
        cancelReason,
        priority,
        null);
  }

  public record Price(long amountCents, String currency, boolean taxIncluded) {}

  public record OrderItemResponse(String productId, String sku, int quantity, Price unitPrice) {
    public OrderItemResponse(String productId, String sku, int quantity) {
      this(productId, sku, quantity, null);
    }
  }

  public record AddressResponse(
      String street, String department, String floor, String postalCode) {}

  public record Timestamps(Instant createdAt, Instant startedAt, Instant completedAt) {}

  public static OrderResponse from(Order order) {
    List<OrderItem> rawItems = order.getItems();
    List<OrderItemResponse> items =
        rawItems == null
            ? List.of()
            : rawItems.stream()
                .map(
                    i ->
                        new OrderItemResponse(
                            i.getProductId(),
                            i.getSku(),
                            i.getQuantity(),
                            i.getUnitPrice() == null
                                ? null
                                : new Price(
                                    i.getUnitPrice().getAmountCents(),
                                    i.getUnitPrice().getCurrency(),
                                    i.getUnitPrice().isTaxIncluded())))
                .toList();

    AddressResponse address = null;
    if (order.getAddress() != null) {
      address =
          new AddressResponse(
              order.getAddress().getStreet(),
              order.getAddress().getDepartment(),
              order.getAddress().getFloor(),
              order.getAddress().getPostalCode());
    }

    Price total =
        order.getTotal() == null
            ? null
            : new Price(
                order.getTotal().getAmountCents(),
                order.getTotal().getCurrency(),
                order.getTotal().isTaxIncluded());

    return new OrderResponse(
        order.getId(),
        order.getStatus(),
        order.getRequestedByUserId(),
        items,
        order.getDestinationArea(),
        order.getAssignedVehicleId(),
        address,
        new Timestamps(order.getCreatedAt(), order.getStartedAt(), order.getCompletedAt()),
        order.getCancelReason(),
        order.getPriority(),
        total);
  }
}
