package com.usal.whbackend.api.order;

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
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String cancelReason) {

    public OrderResponse {
        items = items == null ? null : List.copyOf(items);
    }

    public record OrderItemResponse(String productId, String sku, int quantity) {}
}
