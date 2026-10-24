package com.usal.whbackend.api.restock.order;

import com.usal.whbackend.domain.RestockOrder;
import java.time.Instant;

/**
 * {@code GET /restock/orders/:id} — the order plus how much of it has actually arrived so far,
 * computed from the linked {@code Reception}s rather than stored (see {@code
 * RestockOrderService.computeReceivedSoFar}), so it can never drift from the real history.
 */
public record RestockOrderDetailResponse(
    String id,
    String productId,
    int quantityRequested,
    int quantityReceivedSoFar,
    String supplier,
    String requestedByUserId,
    Instant createdAt) {

  public static RestockOrderDetailResponse from(RestockOrder order, int quantityReceivedSoFar) {
    return new RestockOrderDetailResponse(
        order.getId(),
        order.getProductId(),
        order.getQuantityRequested(),
        quantityReceivedSoFar,
        order.getSupplier(),
        order.getRequestedByUserId(),
        order.getCreatedAt());
  }
}
