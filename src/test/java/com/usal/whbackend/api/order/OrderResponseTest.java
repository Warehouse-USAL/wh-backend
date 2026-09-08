package com.usal.whbackend.api.order;

import static org.junit.jupiter.api.Assertions.*;

import com.usal.whbackend.domain.Address;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderPriority;
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
            "cancelled",
            OrderPriority.HIGH);

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
    assertEquals(OrderPriority.HIGH, response.priority());

    assertEquals("p-1", item.productId());
    assertEquals("SKU-1", item.sku());
    assertEquals(2, item.quantity());
  }

  @Test
  void from_fullOrder_mapsEveryField() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Instant started = Instant.parse("2026-01-02T00:00:00Z");
    Instant completed = Instant.parse("2026-01-03T00:00:00Z");

    Address address = new Address();
    address.setStreet("Av. Corrientes 1234");
    address.setDepartment("4A");
    address.setFloor("4");
    address.setPostalCode("C1043");

    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setPriority(OrderPriority.URGENT);
    order.setRequestedByUserId("usr-1");
    order.setItems(List.of(new OrderItem("prod-1", "SKU-1", 3)));
    order.setDestinationArea("AREA-A");
    order.setAssignedVehicleId("veh-1");
    order.setAddress(address);
    order.setCreatedAt(created);
    order.setStartedAt(started);
    order.setCompletedAt(completed);
    order.setCancelReason("none");

    OrderResponse r = OrderResponse.from(order);

    assertEquals("ord-1", r.id());
    assertEquals(OrderStatus.IN_PROGRESS, r.status());
    assertEquals("usr-1", r.requestedByUserId());
    assertEquals(1, r.items().size());
    assertEquals("prod-1", r.items().get(0).productId());
    assertEquals("SKU-1", r.items().get(0).sku());
    assertEquals(3, r.items().get(0).quantity());
    assertEquals("AREA-A", r.destinationArea());
    assertEquals("veh-1", r.assignedVehicleId());
    assertEquals("Av. Corrientes 1234", r.address().street());
    assertEquals("4A", r.address().department());
    assertEquals("4", r.address().floor());
    assertEquals("C1043", r.address().postalCode());
    assertEquals(created, r.timestamps().createdAt());
    assertEquals(started, r.timestamps().startedAt());
    assertEquals(completed, r.timestamps().completedAt());
    assertEquals("none", r.cancelReason());
    assertEquals(OrderPriority.URGENT, r.priority());
  }

  @Test
  void from_orderWithoutItemsOrAddress_yieldsEmptyItemsAndNullAddress() {
    Order order = new Order();
    order.setId("ord-2");
    order.setStatus(OrderStatus.PENDING);

    OrderResponse r = OrderResponse.from(order);

    assertTrue(r.items().isEmpty());
    assertNull(r.address());
    assertNotNull(r.timestamps());
  }

  @Test
  void constructor_nullItems_staysNull() {
    OrderResponse r =
        new OrderResponse("id", OrderStatus.PENDING, "u", null, null, null, null, null, null, null);
    assertNull(r.items());
  }

  @Test
  void from_orderWithoutPriority_yieldsNullPriority() {
    Order order = new Order();
    order.setId("ord-3");
    order.setStatus(OrderStatus.PENDING);

    OrderResponse r = OrderResponse.from(order);

    assertNull(r.priority());
  }

  @Test
  void order_addressIsDefensivelyCopied() {
    Address address = new Address();
    address.setStreet("Calle 1");
    Order order = new Order();
    order.setAddress(address);

    address.setStreet("mutated");

    assertEquals("Calle 1", order.getAddress().getStreet());

    order.setAddress(null);
    assertNull(order.getAddress());
  }

  @Test
  void createOrderRequest_copiesItemsDefensively() {
    java.util.List<CreateOrderRequest.OrderItemRequest> items =
        new java.util.ArrayList<>(List.of(new CreateOrderRequest.OrderItemRequest("prod-1", 2)));
    CreateOrderRequest request = new CreateOrderRequest(items, "AREA-A", null);
    items.clear();

    assertEquals(1, request.items().size());
    assertEquals("AREA-A", request.destinationArea());
    assertNull(request.address());
  }

  @Test
  void createOrderRequest_nullItems_staysNull() {
    assertNull(new CreateOrderRequest(null, "AREA-A", null).items());
  }

  @Test
  void createOrderRequest_threeArgConstructor_leavesPriorityNull() {
    assertNull(new CreateOrderRequest(List.of(), "AREA-A", null).priority());
  }

  @Test
  void createOrderRequest_fourArgConstructor_carriesPriority() {
    CreateOrderRequest request =
        new CreateOrderRequest(List.of(), "AREA-A", null, OrderPriority.URGENT);
    assertEquals(OrderPriority.URGENT, request.priority());
  }
}
