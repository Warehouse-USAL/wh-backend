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
    OrderResponse.AddressResponse address =
        new OrderResponse.AddressResponse("Av. Corrientes 1234", "4A", "4", "C1043");
    OrderResponse response =
        new OrderResponse(
            "id-1",
            OrderStatus.PENDING,
            "user-1",
            List.of(item),
            "zone-A",
            "vehicle-1",
            address,
            new OrderResponse.Timestamps(now, now, now),
            "cancelled");

    assertEquals("id-1", response.id());
    assertEquals(OrderStatus.PENDING, response.status());
    assertEquals("user-1", response.requestedByUserId());
    assertEquals(1, response.items().size());
    assertEquals("zone-A", response.destinationArea());
    assertEquals("vehicle-1", response.assignedVehicleId());
    assertEquals("Av. Corrientes 1234", response.address().street());
    assertEquals("4A", response.address().department());
    assertEquals("4", response.address().floor());
    assertEquals("C1043", response.address().postalCode());
    assertEquals(now, response.timestamps().createdAt());
    assertEquals(now, response.timestamps().startedAt());
    assertEquals(now, response.timestamps().completedAt());
    assertEquals("cancelled", response.cancelReason());

    assertEquals("p-1", item.productId());
    assertEquals("SKU-1", item.sku());
    assertEquals(2, item.quantity());
  }
}
