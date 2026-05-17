package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderTest {

  @Test
  void gettersAndSetters() {
    Order order = new Order();
    Instant now = Instant.now();

    order.setId("id-1");
    order.setStatus(OrderStatus.PENDING);
    order.setRequestedByUserId("user-1");
    order.setItems(List.of(new OrderItem("p1", "SKU-1", 2)));
    order.setDestinationArea("zone-A");
    order.setAssignedVehicleId("vehicle-1");
    order.setCreatedAt(now);
    order.setStartedAt(now);
    order.setCompletedAt(now);
    order.setCancelReason("out of stock");

    assertEquals("id-1", order.getId());
    assertEquals(OrderStatus.PENDING, order.getStatus());
    assertEquals("user-1", order.getRequestedByUserId());
    assertEquals(1, order.getItems().size());
    assertEquals("zone-A", order.getDestinationArea());
    assertEquals("vehicle-1", order.getAssignedVehicleId());
    assertEquals(now, order.getCreatedAt());
    assertEquals(now, order.getStartedAt());
    assertEquals(now, order.getCompletedAt());
    assertEquals("out of stock", order.getCancelReason());
  }
}
