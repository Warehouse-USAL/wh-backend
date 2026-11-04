package com.usal.whbackend.api.restock.order;

import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.domain.RestockOrderStatus;
import java.time.Duration;
import java.time.Instant;

/**
 * {@code GET /restock/orders/:id} — the order plus how much of it has actually arrived so far,
 * computed from the linked {@code Reception}s rather than stored (see {@code
 * RestockOrderService.computeReceivedSoFar}), so it can never drift from the real history.
 *
 * <p>{@code deliveryLeadTimeDays} is derived on the fly from {@code expectedDeliveryDate -
 * createdAt} (#134) and is {@code null} when no expected date was recorded. The cancellation fields
 * (#137) are only populated once the order has been cancelled.
 */
public record RestockOrderDetailResponse(
    String id,
    String productId,
    int quantityRequested,
    int quantityReceivedSoFar,
    String supplier,
    String requestedByUserId,
    RestockOrderStatus status,
    Instant expectedDeliveryDate,
    Long deliveryLeadTimeDays,
    Instant cancelledAt,
    String cancelledByUserId,
    String cancellationReason,
    Instant createdAt) {

  public static RestockOrderDetailResponse from(RestockOrder order, int quantityReceivedSoFar) {
    return new RestockOrderDetailResponse(
        order.getId(),
        order.getProductId(),
        order.getQuantityRequested(),
        quantityReceivedSoFar,
        order.getSupplier(),
        order.getRequestedByUserId(),
        order.getStatus(),
        order.getExpectedDeliveryDate(),
        computeLeadTimeDays(order),
        order.getCancelledAt(),
        order.getCancelledByUserId(),
        order.getCancellationReason(),
        order.getCreatedAt());
  }

  private static Long computeLeadTimeDays(RestockOrder order) {
    if (order.getExpectedDeliveryDate() == null || order.getCreatedAt() == null) {
      return null;
    }
    return Duration.between(order.getCreatedAt(), order.getExpectedDeliveryDate()).toDays();
  }
}
