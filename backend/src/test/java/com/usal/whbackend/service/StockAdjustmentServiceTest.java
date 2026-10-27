package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.domain.StockAdjustmentType;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.StockAdjustmentRepository;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentServiceTest {

  @Mock PositionRepository positionRepository;
  @Mock ProductRepository productRepository;
  @Mock OrderRepository orderRepository;
  @Mock StockAdjustmentRepository adjustmentRepository;
  @Mock OrderEventPublisher orderEventPublisher;
  StockAdjustmentService service;

  @BeforeEach
  void setUp() {
    service =
        new StockAdjustmentService(
            positionRepository,
            productRepository,
            orderRepository,
            adjustmentRepository,
            List.of(orderEventPublisher));
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private static Position position(int stock) {
    Position p = new Position();
    p.setId("pos-1");
    p.setProductId("prod-1");
    p.setActive(true);
    p.setCurrentStock(stock);
    return p;
  }

  private static Order order(String id, OrderStatus status, int minute, int quantity) {
    Order o = new Order();
    o.setId(id);
    o.setStatus(status);
    o.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(minute * 60L));
    o.setItems(List.of(new OrderItem("prod-1", "SKU-1", quantity)));
    return o;
  }

  /** Mirrors the 14/6 example: 8 units remain in the only position, in-process 5, pending 4, 2. */
  private void stubSpecExample() {
    Position p = position(14);
    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    when(orderRepository.findActiveByProductId("prod-1"))
        .thenReturn(
            List.of(
                order("in-process", OrderStatus.IN_PROGRESS, 0, 5),
                order("pending-4", OrderStatus.PENDING, 1, 4),
                order("pending-2", OrderStatus.PENDING, 2, 2)));
    lenient()
        .when(orderRepository.cancelWithoutPublishing(any(), anyString()))
        .thenAnswer(inv -> inv.getArgument(0));
    lenient().when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void adjust_specExample_cancelsOnlyTheOrderThatDoesNotFit() {
    stubSpecExample();
    SecurityContextHolder.getContext()
        .setAuthentication(new TestingAuthenticationToken("user-7", "x"));

    StockAdjustmentResult result = service.adjust("pos-1", 6, StockAdjustmentType.DAMAGE, " rota ");

    StockAdjustment a = result.adjustment();
    assertEquals(14, a.quantityBefore());
    assertEquals(8, a.quantityAfter());
    assertEquals(StockAdjustmentType.DAMAGE, a.type());
    assertEquals(" rota ", a.reason());
    assertEquals("user-7", a.adjustedBy());
    assertEquals("pos-1", a.positionId());
    assertEquals("prod-1", a.productId());
    assertEquals(List.of("pending-4"), a.cancelledOrderIds());
    assertEquals(
        List.of("pending-4"), result.cancelledOrders().stream().map(Order::getId).toList());

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(orderRepository).cancelWithoutPublishing(any(), reason.capture());
    assertThat(reason.getValue()).startsWith("INSUFFICIENT_STOCK: ");
    verify(positionRepository).save(argThatStock(8));
  }

  private static Position argThatStock(int stock) {
    return org.mockito.ArgumentMatchers.argThat(p -> p.getCurrentStock() == stock);
  }

  @Test
  void adjust_savesStockThenCancelsThenRecordsHistory() {
    stubSpecExample();

    service.adjust("pos-1", 6, null, null);

    InOrder order = inOrder(positionRepository, orderRepository, adjustmentRepository);
    order.verify(positionRepository).save(any());
    order.verify(orderRepository).cancelWithoutPublishing(any(), anyString());
    order.verify(adjustmentRepository).save(any());
  }

  @Test
  void adjust_defaultsToManualDecrease_blankReasonBecomesNull_andActorToSystem() {
    stubSpecExample();

    StockAdjustment a = service.adjust("pos-1", 1, null, "  ").adjustment();

    assertEquals(StockAdjustmentType.MANUAL_DECREASE, a.type());
    assertNull(a.reason());
    assertEquals("system", a.adjustedBy());
    assertThat(a.cancelledOrderIds()).isEmpty();
  }

  @Test
  void adjust_failureWhileCancelling_propagatesAndRecordsAndPublishesNothing() {
    Position p = position(14);
    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    when(orderRepository.findActiveByProductId("prod-1"))
        .thenReturn(List.of(order("pending", OrderStatus.PENDING, 1, 12)));
    when(orderRepository.cancelWithoutPublishing(any(), anyString()))
        .thenThrow(new IllegalStateException("mongo down"));

    // The exception leaves the @Transactional method, which is what rolls the stock change back.
    assertThrows(IllegalStateException.class, () -> service.adjust("pos-1", 6, null, null));

    verify(adjustmentRepository, never()).save(any());
    verify(orderRepository, never()).publishCancelled(any());
    verify(orderEventPublisher, never()).broadcastOrderUpdate(any());
  }

  @Test
  void adjust_failureWhileRecordingHistory_publishesNothing() {
    stubSpecExample();
    when(adjustmentRepository.save(any())).thenThrow(new IllegalStateException("mongo down"));

    TransactionSynchronizationManager.initSynchronization();
    try {
      assertThrows(IllegalStateException.class, () -> service.adjust("pos-1", 6, null, null));
      // No commit happens, so the registered callbacks (if any) are never run: nothing published.
      verify(orderRepository, never()).publishCancelled(any());
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void adjust_publishesOnlyAfterCommit() {
    stubSpecExample();

    TransactionSynchronizationManager.initSynchronization();
    try {
      StockAdjustmentResult result = service.adjust("pos-1", 6, null, null);
      Order cancelled = result.cancelledOrders().get(0);

      verify(orderRepository, never()).publishCancelled(any());
      verify(orderEventPublisher, never()).broadcastOrderUpdate(any());

      TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());

      verify(orderRepository).publishCancelled(cancelled);
      verify(orderEventPublisher).broadcastOrderUpdate(cancelled);
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void adjust_withoutATransaction_publishesAtOnce() {
    stubSpecExample();

    StockAdjustmentResult result = service.adjust("pos-1", 6, null, null);

    verify(orderRepository).publishCancelled(result.cancelledOrders().get(0));
  }

  @Test
  void adjust_aFailingPublishDoesNotFailTheCall_andTheNextOrderIsStillAnnounced() {
    Position p = position(14);
    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("prod-1")))
        .thenReturn(List.of(p));
    Order first = order("first", OrderStatus.PENDING, 1, 10);
    Order second = order("second", OrderStatus.PENDING, 2, 10);
    when(orderRepository.findActiveByProductId("prod-1")).thenReturn(List.of(first, second));
    when(orderRepository.cancelWithoutPublishing(any(), anyString()))
        .thenAnswer(inv -> inv.getArgument(0));
    when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    doThrow(new RuntimeException("kafka")).when(orderRepository).publishCancelled(first);

    StockAdjustmentResult result = service.adjust("pos-1", 6, null, null);

    // 8 remain: first (10) is cancelled and so is second (10).
    assertEquals(2, result.cancelledOrders().size());
    verify(orderRepository).publishCancelled(second);
  }

  @Test
  void adjust_inactivePosition_doesNotReconcile() {
    Position p = position(14);
    p.setActive(false);
    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(p));
    when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    StockAdjustmentResult result = service.adjust("pos-1", 6, null, null);

    assertThat(result.cancelledOrders()).isEmpty();
    verify(orderRepository, never()).findActiveByProductId(anyString());
  }

  @Test
  void adjust_rejectsInvalidInput() {
    assertEquals("INVALID_QUANTITY", reasonOf(() -> service.adjust("pos-1", 0, null, null)));
    assertEquals(
        "INVALID_ADJUSTMENT_TYPE",
        reasonOf(() -> service.adjust("pos-1", 1, StockAdjustmentType.MANUAL_INCREASE, null)));
    assertEquals(
        "INVALID_ADJUSTMENT_TYPE",
        reasonOf(() -> service.adjust("pos-1", 1, StockAdjustmentType.DEACTIVATED, null)));

    Position noProduct = position(5);
    noProduct.setProductId(null);
    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(noProduct));
    assertEquals(
        "POSITION_WITHOUT_PRODUCT", reasonOf(() -> service.adjust("pos-1", 1, null, null)));

    when(positionRepository.findById("pos-1")).thenReturn(Optional.of(position(5)));
    assertEquals(
        "QUANTITY_EXCEEDS_POSITION_STOCK", reasonOf(() -> service.adjust("pos-1", 6, null, null)));

    verify(positionRepository, never()).save(any());
    verify(adjustmentRepository, never()).save(any());
  }

  @Test
  void adjust_unknownPosition_throwsNotFound() {
    when(positionRepository.findById("ghost")).thenReturn(Optional.empty());

    assertThrows(PositionNotFoundException.class, () -> service.adjust("ghost", 1, null, null));
  }

  @Test
  void recordChange_withoutReconcileProduct_onlyRecords() {
    when(adjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    StockAdjustmentResult result =
        service.recordChange(
            new StockChange(
                "pos-1", "prod-1", StockAdjustmentType.MANUAL_INCREASE, 2, 9, null, "conteo"));

    assertEquals(StockAdjustmentType.MANUAL_INCREASE, result.adjustment().type());
    assertEquals(2, result.adjustment().quantityBefore());
    assertEquals(9, result.adjustment().quantityAfter());
    verify(orderRepository, never()).findActiveByProductId(anyString());
  }

  @Test
  void recordChange_runsInTheCallersTransaction() throws Exception {
    Method adjust =
        StockAdjustmentService.class.getMethod(
            "adjust", String.class, int.class, StockAdjustmentType.class, String.class);
    Method record = StockAdjustmentService.class.getMethod("recordChange", StockChange.class);

    // Default propagation REQUIRED: joins PositionService's transaction, or opens its own.
    assertEquals(
        org.springframework.transaction.annotation.Propagation.REQUIRED,
        adjust.getAnnotation(Transactional.class).propagation());
    assertEquals(
        org.springframework.transaction.annotation.Propagation.REQUIRED,
        record.getAnnotation(Transactional.class).propagation());
  }

  @Test
  void history_unknownPosition_throwsNotFound() {
    when(positionRepository.existsById("ghost")).thenReturn(false);

    assertThrows(
        PositionNotFoundException.class,
        () -> service.getPositionHistory("ghost", PageRequest.of(0, 10)));
  }

  @Test
  void history_unknownProduct_throws404() {
    when(productRepository.existsById("ghost")).thenReturn(false);

    ResponseStatusException e =
        assertThrows(
            ResponseStatusException.class,
            () -> service.getProductHistory("ghost", PageRequest.of(0, 10)));
    assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
  }

  @Test
  void history_returnsTheRepositoryPages() {
    PageRequest pageable = PageRequest.of(0, 10);
    Page<StockAdjustment> page =
        new PageImpl<>(
            List.of(
                new StockAdjustment(
                    "a1",
                    "pos-1",
                    "prod-1",
                    StockAdjustmentType.DAMAGE,
                    null,
                    5,
                    3,
                    "u",
                    null,
                    null)));
    when(positionRepository.existsById("pos-1")).thenReturn(true);
    when(productRepository.existsById("prod-1")).thenReturn(true);
    when(adjustmentRepository.findByPositionIdOrderByCreatedAtDesc("pos-1", pageable))
        .thenReturn(page);
    when(adjustmentRepository.findByProductIdOrderByCreatedAtDesc("prod-1", pageable))
        .thenReturn(page);

    assertEquals(page, service.getPositionHistory("pos-1", pageable));
    assertEquals(page, service.getProductHistory("prod-1", pageable));
  }

  private static String reasonOf(org.junit.jupiter.api.function.Executable call) {
    return assertThrows(ResponseStatusException.class, call).getReason();
  }
}
