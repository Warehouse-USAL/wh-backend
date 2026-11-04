package com.usal.whbackend.api.restock.order;

import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.RestockOrderStatus;
import java.time.Instant;

public record RestockOrderResponse(
    String id,
    String productId,
    int quantityRequested,
    String supplier,
    String requestedByUserId,
    RestockOrderStatus status,
    Instant expectedDeliveryDate,
    Instant createdAt) {

  public static RestockOrderResponse from(RestockOrder order) {
    return new RestockOrderResponse(
        order.getId(),
        order.getProductId(),
        order.getQuantityRequested(),
        order.getSupplier(),
        order.getRequestedByUserId(),
        order.getStatus(),
        order.getExpectedDeliveryDate(),
        order.getCreatedAt());
  }
}
