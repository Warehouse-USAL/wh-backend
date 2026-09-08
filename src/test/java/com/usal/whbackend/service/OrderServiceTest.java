package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.AddressRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.OrderItemRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.VehicleRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock ProductRepository productRepository;
  @Mock ProductService productService;
  @Mock VehicleRepository vehicleRepository;
  @Mock StockDrainPort stockDrainPort;
  @Mock OrderEventPublisher orderEventPublisher;
  @Mock StockEventPublisher stockEventPublisher;
  OrderService orderService;

  @BeforeEach
  void setUp() {
    orderService =
        new OrderService(
            orderRepository,
            productRepository,
            productService,
            vehicleRepository,
            stockDrainPort,
            List.of(orderEventPublisher),
            List.of(stockEventPublisher));
  }

  // ── getOrders ──────────────────────────────────────────────────────────────

  @Test
  void getOrders_noFilters_returnsAll() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o1 = new Order();
    Order o2 = new Order();
    when(orderRepository.findByFilters(null, null, null, null, pageable))
        .thenReturn(new PageImpl<>(List.of(o1, o2), pageable, 2));

    Page<Order> result = orderService.getOrders(null, null, null, null, pageable);

    assertEquals(2, result.getContent().size());
  }

  @Test
  void getOrders_statusFilter_parsesAndPassesToRepo() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o1 = new Order();
    o1.setStatus(OrderStatus.PENDING);
    when(orderRepository.findByFilters(
            eq(OrderStatus.PENDING), isNull(), isNull(), isNull(), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(o1), pageable, 1));

    Page<Order> result = orderService.getOrders("PENDING", null, null, null, pageable);

    assertEquals(1, result.getContent().size());
    assertEquals(OrderStatus.PENDING, result.getContent().get(0).getStatus());
  }

  @Test
  void getOrders_invalidStatus_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders("INVALIDO", null, null, null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_STATUS", ex.getReason());
  }

  @Test
  void getOrders_invalidFromDate_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders(null, "not-a-date", null, null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getOrders_invalidToDate_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.getOrders(null, null, "not-a-date", null, pageable));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_DATE_FORMAT", ex.getReason());
  }

  @Test
  void getOrders_validDateRange_parsesInstantsAndPassesToRepo() {
    Pageable pageable = PageRequest.of(0, 10);
    String fromStr = "2026-01-01T00:00:00Z";
    String toStr = "2026-12-31T23:59:59Z";
    Instant fromInstant = Instant.parse(fromStr);
    Instant toInstant = Instant.parse(toStr);
    when(orderRepository.findByFilters(
            isNull(), isNull(), eq(fromInstant), eq(toInstant), eq(pageable)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Page<Order> result = orderService.getOrders(null, fromStr, toStr, null, pageable);

    assertEquals(0, result.getContent().size());
    verify(orderRepository).findByFilters(null, null, fromInstant, toInstant, pageable);
  }

  // ── getOrder ───────────────────────────────────────────────────────────────

  @Test
  void getOrder_existingId_returnsOrder() {
    Order order = new Order();
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

    Order result = orderService.getOrder("id-1");

    assertEquals(OrderStatus.PENDING, result.getStatus());
  }

  @Test
  void getOrder_unknownId_throws404() {
    when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.getOrder("no-existe"));

    assertEquals(404, ex.getStatusCode().value());
  }

  private static AddressRequest validAddress() {
    return new AddressRequest("Av. Corrientes 1234", null, null, "C1043");
  }

  // ── createOrder ────────────────────────────────────────────────────────────

  @Test
  void createOrder_missingDestination_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(List.of(), null, null);
    assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_emptyItems_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(List.of(), "AREA-A", null);
    assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_missingAddress_throws400() {
    CreateOrderRequest req =
        new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 1)), "AREA-A", null);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("MISSING_ADDRESS", ex.getReason());
  }

  @Test
  void createOrder_missingAddressStreet_throws400() {
    AddressRequest addr = new AddressRequest(null, null, null, "C1043");
    CreateOrderRequest req =
        new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 1)), "AREA-A", addr);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("MISSING_ADDRESS_STREET", ex.getReason());
  }

  @Test
  void createOrder_missingAddressPostalCode_throws400() {
    AddressRequest addr = new AddressRequest("Av. Corrientes 1234", null, null, null);
    CreateOrderRequest req =
        new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 1)), "AREA-A", addr);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("MISSING_ADDRESS_POSTAL_CODE", ex.getReason());
  }

  @Test
  void createOrder_validRequest_savesAndPublishes() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setMaxQuantityPerOrder(5);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    when(productService.computeAvailableStock("prod-1")).thenReturn(10);
    when(productService.computeReservedStock("prod-1")).thenReturn(0);
    Order saved = new Order();
    saved.setId("ord-new");
    when(orderRepository.save(any())).thenReturn(saved);

    Order result =
        orderService.createOrder(
            new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 2)), "AREA-A", validAddress()),
            "user-1");

    assertEquals("ord-new", result.getId());
    verify(orderRepository).save(any());
    verify(orderEventPublisher).broadcastOrderUpdate(saved);
  }

  @Test
  void createOrder_omittedPriority_defaultsToMedium() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setMaxQuantityPerOrder(5);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    when(productService.computeAvailableStock("prod-1")).thenReturn(10);
    when(productService.computeReservedStock("prod-1")).thenReturn(0);
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result =
        orderService.createOrder(
            new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 2)), "AREA-A", validAddress()),
            "user-1");

    assertEquals(com.usal.whbackend.domain.OrderPriority.MEDIUM, result.getPriority());
  }

  @Test
  void createOrder_explicitPriority_isRespected() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setMaxQuantityPerOrder(5);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    when(productService.computeAvailableStock("prod-1")).thenReturn(10);
    when(productService.computeReservedStock("prod-1")).thenReturn(0);
    when(orderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result =
        orderService.createOrder(
            new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 2)),
                "AREA-A",
                validAddress(),
                com.usal.whbackend.domain.OrderPriority.URGENT),
            "user-1");

    assertEquals(com.usal.whbackend.domain.OrderPriority.URGENT, result.getPriority());
  }

  // ── cancelOrder ────────────────────────────────────────────────────────────

  @Test
  void cancelOrder_pendingOrder_cancelsAndPublishes() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    order.setItems(List.of());
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.cancel(any(), any())).thenReturn(order);

    orderService.cancelOrder("ord-1", "reason");

    verify(orderRepository).cancel(order, "reason");
    verify(orderEventPublisher).broadcastOrderUpdate(order);
  }

  @Test
  void cancelOrder_completedOrder_throws409() {
    Order order = new Order();
    order.setStatus(OrderStatus.COMPLETED);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.cancelOrder("ord-1", null));

    assertEquals(409, ex.getStatusCode().value());
  }

  // ── cancelOrder - missing path ─────────────────────────────────────────────

  @Test
  void cancelOrder_unknownId_throws404() {
    when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.cancelOrder("no-existe", null));

    assertEquals(404, ex.getStatusCode().value());
  }

  // ── createOrder - validation paths ────────────────────────────────────────

  @Test
  void createOrder_invalidQuantity_throws400() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                orderService.createOrder(
                    new CreateOrderRequest(
                        List.of(new OrderItemRequest("prod-1", 0)), "AREA-A", validAddress()),
                    "user-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void createOrder_duplicateProduct_throws400() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                orderService.createOrder(
                    new CreateOrderRequest(
                        List.of(
                            new OrderItemRequest("prod-1", 1), new OrderItemRequest("prod-1", 2)),
                        "AREA-A",
                        validAddress()),
                    "user-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void createOrder_productNotFound_throws404() {
    when(productRepository.findById("missing")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                orderService.createOrder(
                    new CreateOrderRequest(
                        List.of(new OrderItemRequest("missing", 1)), "AREA-A", validAddress()),
                    "user-1"));

    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void createOrder_insufficientStock_throws400() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setMaxQuantityPerOrder(20);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    when(productService.computeAvailableStock("prod-1")).thenReturn(5);
    when(productService.computeReservedStock("prod-1")).thenReturn(0);

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                orderService.createOrder(
                    new CreateOrderRequest(
                        List.of(new OrderItemRequest("prod-1", 10)), "AREA-A", validAddress()),
                    "user-1"));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INSUFFICIENT_STOCK", ex.getReason());
  }

  // ── assignVehicle ──────────────────────────────────────────────────────────

  @Test
  void assignVehicle_pendingOrder_setsInProgressAndStartedAt() {
    Order order = new Order();
    order.setId("order-1");
    order.setStatus(OrderStatus.PENDING);

    Vehicle vehicle = new Vehicle();
    vehicle.setId("vehicle-1");

    Order saved = new Order();
    saved.setId("order-1");
    saved.setStatus(OrderStatus.IN_PROGRESS);
    saved.setAssignedVehicleId("vehicle-1");

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("vehicle-1")).thenReturn(Optional.of(vehicle));
    when(orderRepository.update(any())).thenReturn(saved);

    Order result = orderService.assignVehicle("order-1", "vehicle-1");

    assertEquals(OrderStatus.IN_PROGRESS, result.getStatus());
    assertEquals("vehicle-1", result.getAssignedVehicleId());
    verify(vehicleRepository).save(vehicle);
    assertEquals(VehicleStatus.BUSY, vehicle.getStatus());
    assertEquals("order-1", vehicle.getCurrentOrderId());
    verify(orderEventPublisher).broadcastOrderUpdate(saved);
  }

  @Test
  void assignVehicle_inProgressOrder_doesNotResetStartedAt() {
    Instant originalStart = Instant.parse("2026-01-01T00:00:00Z");
    Order order = new Order();
    order.setId("order-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setStartedAt(originalStart);
    order.setAssignedVehicleId("vehicle-old");

    Vehicle newVehicle = new Vehicle();
    newVehicle.setId("vehicle-2");

    Vehicle oldVehicle = new Vehicle();
    oldVehicle.setId("vehicle-old");

    Order saved = new Order();
    saved.setId("order-1");
    saved.setStatus(OrderStatus.IN_PROGRESS);

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("vehicle-2")).thenReturn(Optional.of(newVehicle));
    when(vehicleRepository.findById("vehicle-old")).thenReturn(Optional.of(oldVehicle));
    when(orderRepository.update(any())).thenReturn(saved);

    orderService.assignVehicle("order-1", "vehicle-2");

    assertEquals(originalStart, order.getStartedAt());
    assertEquals(VehicleStatus.IDLE, oldVehicle.getStatus());
    assertNull(oldVehicle.getCurrentOrderId());
    verify(vehicleRepository).save(oldVehicle);
  }

  @Test
  void assignVehicle_orderNotFound_throws404() {
    when(orderRepository.findById("missing")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.assignVehicle("missing", "vehicle-1"));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_FOUND", ex.getReason());
  }

  @Test
  void assignVehicle_vehicleNotFound_throws404() {
    Order order = new Order();
    order.setId("order-1");
    order.setStatus(OrderStatus.PENDING);

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("vehicle-x")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.assignVehicle("order-1", "vehicle-x"));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("VEHICLE_NOT_FOUND", ex.getReason());
  }

  @Test
  void assignVehicle_completedOrder_throws409() {
    Order order = new Order();
    order.setId("order-1");
    order.setStatus(OrderStatus.COMPLETED);

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.assignVehicle("order-1", "vehicle-1"));

    assertEquals(409, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_ASSIGNABLE", ex.getReason());
  }

  @Test
  void assignVehicle_cancelledOrder_throws409() {
    Order order = new Order();
    order.setId("order-1");
    order.setStatus(OrderStatus.CANCELLED);

    when(orderRepository.findById("order-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.assignVehicle("order-1", "vehicle-1"));

    assertEquals(409, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_ASSIGNABLE", ex.getReason());
  }

  // ── changeStatus ───────────────────────────────────────────────────────────

  @Test
  void changeStatus_toCompleted_setsCompletedAtAndDrainsStock() {
    List<OrderItem> items = List.of(new OrderItem("prod-1", "SKU-1", 3));
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(items);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.changeStatus("ord-1", "completed");

    assertEquals(OrderStatus.COMPLETED, result.getStatus());
    assertNotNull(result.getCompletedAt());
    verify(stockDrainPort).drain(items);
    verify(orderEventPublisher).broadcastOrderUpdate(result);
  }

  @Test
  void changeStatus_toInProgress_setsStartedAtAndDoesNotDrain() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.changeStatus("ord-1", "in_progress");

    assertEquals(OrderStatus.IN_PROGRESS, result.getStatus());
    assertNotNull(result.getStartedAt());
    verify(stockDrainPort, never()).drain(any());
  }

  @Test
  void changeStatus_toInProgress_doesNotResetExistingStartedAt() {
    Instant original = Instant.parse("2026-01-01T00:00:00Z");
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setStartedAt(original);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.changeStatus("ord-1", "in_progress");

    assertEquals(original, result.getStartedAt());
  }

  @Test
  void changeStatus_toCancelled_setsCancelledAndDoesNotDrain() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.changeStatus("ord-1", "cancelled");

    assertEquals(OrderStatus.CANCELLED, result.getStatus());
    verify(stockDrainPort, never()).drain(any());
  }

  @Test
  void changeStatus_completedOrder_throws409() {
    Order order = new Order();
    order.setStatus(OrderStatus.COMPLETED);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.changeStatus("ord-1", "in_progress"));

    assertEquals(409, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_MODIFIABLE", ex.getReason());
  }

  @Test
  void changeStatus_cancelledOrder_throws409() {
    Order order = new Order();
    order.setStatus(OrderStatus.CANCELLED);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.changeStatus("ord-1", "completed"));

    assertEquals(409, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_MODIFIABLE", ex.getReason());
  }

  @Test
  void changeStatus_invalidStatus_throws400() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.changeStatus("ord-1", "bogus"));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_STATUS", ex.getReason());
  }

  @Test
  void changeStatus_nullStatus_throws400() {
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.changeStatus("ord-1", null));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_STATUS", ex.getReason());
  }

  @Test
  void changeStatus_toPending_throws400() {
    Order order = new Order();
    order.setStatus(OrderStatus.PENDING);
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.changeStatus("ord-1", "pending"));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_STATUS_TRANSITION", ex.getReason());
  }

  @Test
  void changeStatus_unknownId_throws404() {
    when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> orderService.changeStatus("no-existe", "completed"));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("ORDER_NOT_FOUND", ex.getReason());
  }

  @Test
  void createOrder_blankDestination_throws400() {
    CreateOrderRequest req =
        new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 1)), "   ", validAddress());
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("DESTINATION_AREA_REQUIRED", ex.getReason());
  }

  @Test
  void createOrder_nullItems_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(null, "AREA-A", validAddress());
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("ITEMS_REQUIRED", ex.getReason());
  }

  @Test
  void createOrder_blankAddressStreet_throws400() {
    CreateOrderRequest req =
        new CreateOrderRequest(
            List.of(new OrderItemRequest("prod-1", 1)),
            "AREA-A",
            new AddressRequest("  ", null, null, "C1043"));
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("MISSING_ADDRESS_STREET", ex.getReason());
  }

  @Test
  void createOrder_blankAddressPostalCode_throws400() {
    CreateOrderRequest req =
        new CreateOrderRequest(
            List.of(new OrderItemRequest("prod-1", 1)),
            "AREA-A",
            new AddressRequest("Av. Corrientes 1234", null, null, "  "));
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("MISSING_ADDRESS_POSTAL_CODE", ex.getReason());
  }

  @Test
  void createOrder_inactiveProduct_throws400() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(false);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));

    CreateOrderRequest req =
        new CreateOrderRequest(
            List.of(new OrderItemRequest("prod-1", 1)), "AREA-A", validAddress());
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("PRODUCT_INACTIVE", ex.getReason());
  }

  @Test
  void createOrder_quantityAboveProductLimit_throws400() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setMaxQuantityPerOrder(3);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));

    CreateOrderRequest req =
        new CreateOrderRequest(
            List.of(new OrderItemRequest("prod-1", 4)), "AREA-A", validAddress());
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
    assertEquals("QUANTITY_EXCEEDS_LIMIT", ex.getReason());
  }

  @Test
  void assignVehicle_vehicleBusyWithAnotherOrder_throws409() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    Vehicle vehicle = new Vehicle();
    vehicle.setId("veh-1");
    vehicle.setStatus(VehicleStatus.BUSY);
    vehicle.setCurrentOrderId("ord-other");
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("veh-1")).thenReturn(Optional.of(vehicle));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> orderService.assignVehicle("ord-1", "veh-1"));
    assertEquals(409, ex.getStatusCode().value());
    assertEquals("VEHICLE_ALREADY_BUSY", ex.getReason());
  }

  @Test
  void assignVehicle_vehicleBusyWithSameOrder_isAllowed() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    Vehicle vehicle = new Vehicle();
    vehicle.setId("veh-1");
    vehicle.setStatus(VehicleStatus.BUSY);
    vehicle.setCurrentOrderId("ord-1");
    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("veh-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.assignVehicle("ord-1", "veh-1");

    assertEquals("veh-1", result.getAssignedVehicleId());
  }

  @Test
  void assignVehicle_reassignment_releasesThePreviousVehicle() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setAssignedVehicleId("veh-old");

    Vehicle newVehicle = new Vehicle();
    newVehicle.setId("veh-new");
    newVehicle.setStatus(VehicleStatus.IDLE);
    Vehicle oldVehicle = new Vehicle();
    oldVehicle.setId("veh-old");
    oldVehicle.setStatus(VehicleStatus.BUSY);
    oldVehicle.setCurrentOrderId("ord-1");

    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("veh-new")).thenReturn(Optional.of(newVehicle));
    when(vehicleRepository.findById("veh-old")).thenReturn(Optional.of(oldVehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    Order result = orderService.assignVehicle("ord-1", "veh-new");

    assertEquals("veh-new", result.getAssignedVehicleId());
    assertNull(oldVehicle.getCurrentOrderId());
    assertEquals(VehicleStatus.IDLE, oldVehicle.getStatus());
  }

  @Test
  void assignVehicle_reassignmentWhenPreviousVehicleIsGone_stillSucceeds() {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setAssignedVehicleId("veh-old");

    Vehicle newVehicle = new Vehicle();
    newVehicle.setId("veh-new");
    newVehicle.setStatus(VehicleStatus.IDLE);

    when(orderRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(vehicleRepository.findById("veh-new")).thenReturn(Optional.of(newVehicle));
    when(vehicleRepository.findById("veh-old")).thenReturn(Optional.empty());
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(orderRepository.update(any())).thenAnswer(inv -> inv.getArgument(0));

    assertEquals("veh-new", orderService.assignVehicle("ord-1", "veh-new").getAssignedVehicleId());
  }
}
