package com.usal.whbackend.api.order;

import static org.junit.jupiter.api.Assertions.*;

import com.usal.whbackend.domain.OrderStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderResponseTest {

    @Test
    void recordAccessors() {
        Instant now = Instant.now();
        OrderResponse.OrderItemResponse item = new OrderResponse.OrderItemResponse("p-1", "SKU-1", 2);
        OrderResponse response = new OrderResponse(
                "id-1", OrderStatus.PENDING, "user-1", List.of(item),
                "zone-A", "vehicle-1", now, now, now, "cancelled");

        assertEquals("id-1", response.id());
        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals("user-1", response.requestedByUserId());
        assertEquals(1, response.items().size());
        assertEquals("zone-A", response.destinationArea());
        assertEquals("vehicle-1", response.assignedVehicleId());
        assertEquals(now, response.createdAt());
        assertEquals(now, response.startedAt());
        assertEquals(now, response.completedAt());
        assertEquals("cancelled", response.cancelReason());

        assertEquals("p-1", item.productId());
        assertEquals("SKU-1", item.sku());
        assertEquals(2, item.quantity());
    }
}
