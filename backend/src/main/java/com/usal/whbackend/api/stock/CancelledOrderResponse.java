package com.usal.whbackend.api.stock;

import com.usal.whbackend.domain.Order;
import java.time.Instant;

/** An order cancelled by a stock adjustment, with the reason recorded on it. */
public record CancelledOrderResponse(String id, String cancelReason, Instant createdAt) {

  public static CancelledOrderResponse from(Order order) {
    return new CancelledOrderResponse(order.getId(), order.getCancelReason(), order.getCreatedAt());
  }
}
