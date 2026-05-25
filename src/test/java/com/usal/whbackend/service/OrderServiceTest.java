package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.OrderItemRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
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
  @Mock OrderEventPublisher orderEventPublisher;
  @Mock StockEventPublisher stockEventPublisher;
  OrderService orderService;

  @BeforeEach
  void setUp() {
    orderService =
        new OrderService(
            orderRepository,
            productRepository,
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

  // ── createOrder ────────────────────────────────────────────────────────────

  @Test
  void createOrder_missingDestination_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(List.of(), null);
    assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_emptyItems_throws400() {
    CreateOrderRequest req = new CreateOrderRequest(List.of(), "AREA-A");
    assertThrows(ResponseStatusException.class, () -> orderService.createOrder(req, "user-1"));
  }

  @Test
  void createOrder_validRequest_savesAndPublishes() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setAvailableStock(10);
    p.setMaxQuantityPerOrder(5);
    p.setMinimumStock(2);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));
    Order saved = new Order();
    saved.setId("ord-new");
    when(orderRepository.save(any())).thenReturn(saved);

    Order result =
        orderService.createOrder(
            new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 2)), "AREA-A"), "user-1");

    assertEquals("ord-new", result.getId());
    verify(orderRepository).save(any());
    verify(orderEventPublisher).broadcastOrderUpdate(saved);
  }

  @Test
  void createOrder_insufficientStock_throws400() {
    Product p = new Product();
    p.setId("prod-1");
    p.setActive(true);
    p.setAvailableStock(1);
    p.setMaxQuantityPerOrder(5);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(p));

    assertThrows(
        ResponseStatusException.class,
        () ->
            orderService.createOrder(
                new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 3)), "AREA-A"),
                "user-1"));
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
                    new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 0)), "AREA-A"),
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
                        "AREA-A"),
                    "user-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void createOrder_productNotFound_throws400() {
    when(productRepository.findById("missing")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                orderService.createOrder(
                    new CreateOrderRequest(List.of(new OrderItemRequest("missing", 1)), "AREA-A"),
                    "user-1"));

    assertEquals(400, ex.getStatusCode().value());
  }

  @Test
  void createOrder_stockBelowMinimum_triggersStockAlert() {
    Product p = new Product();
    p.setId("prod-1");
    p.setSku("SKU-001");
    p.setActive(true);
    p.setAvailableStock(5);
    p.setMaxQuantityPerOrder(10);
    p.setMinimumStock(3);

    // After stock update, available stock drops to 2 (below minimumStock of 3)
    Product updatedProduct = new Product();
    updatedProduct.setId("prod-1");
    updatedProduct.setAvailableStock(2);
    updatedProduct.setMinimumStock(3);

    when(productRepository.findById("prod-1"))
        .thenReturn(Optional.of(p))
        .thenReturn(Optional.of(updatedProduct));
    Order saved = new Order();
    saved.setId("ord-new");
    when(orderRepository.save(any())).thenReturn(saved);

    orderService.createOrder(
        new CreateOrderRequest(List.of(new OrderItemRequest("prod-1", 3)), "AREA-A"), "user-1");

    verify(stockEventPublisher).broadcastStockAlert(updatedProduct);
  }
}
