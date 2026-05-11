package com.usal.whbackend.api.order;

import com.usal.whbackend.domain.Order;
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

    // Factory method: convierte una entidad Order al DTO de respuesta (punto 6 del review)
    public static OrderResponse from(Order order) {
        List<OrderItemResponse> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream()
                        .map(i -> new OrderItemResponse(i.getProductId(), i.getSku(), i.getQuantity()))
                        .toList();

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getRequestedByUserId(),
                items,
                order.getDestinationArea(),
                order.getAssignedVehicleId(),
                order.getCreatedAt(),
                order.getStartedAt(),
                order.getCompletedAt(),
                order.getCancelReason());
    }
}
