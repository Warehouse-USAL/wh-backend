package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.domain.StockAdjustmentType;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.StockAdjustmentRepository;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Records every manual stock change and re-checks the product's orders against what is left, in the
 * transaction of the change itself: the position, the history entry and the cancelled orders are
 * saved together or not at all. Infra and the WebSocket feeds hear about the cancellations only
 * after the commit, so a rolled-back change never publishes anything.
 *
 * <p>The order-fulfilment drain ({@link StockDrainService}) is not an adjustment and does not come
 * through here.
 */
@Service
public class StockAdjustmentService {

  private static final Logger log = LoggerFactory.getLogger(StockAdjustmentService.class);

  /** Recorded as the author when a change happens outside any authenticated request. */
  static final String SYSTEM_ACTOR = "system";

  private final PositionRepository positionRepository;
  private final ProductRepository productRepository;
  private final OrderRepository orderRepository;
  private final StockAdjustmentRepository adjustmentRepository;
  private final List<OrderEventPublisher> orderEventPublishers;

  public StockAdjustmentService(
      PositionRepository positionRepository,
      ProductRepository productRepository,
      OrderRepository orderRepository,
      StockAdjustmentRepository adjustmentRepository,
      List<OrderEventPublisher> orderEventPublishers) {
    this.positionRepository = positionRepository;
    this.productRepository = productRepository;
    this.orderRepository = orderRepository;
    this.adjustmentRepository = adjustmentRepository;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
  }

  /**
   * Lowers a position's stock by {@code quantity} and reconciles the product's orders. Validates
   * like the web did: a whole quantity above zero and not above the position's stock; neither the
   * product's minimum stock nor what is reserved is considered.
   *
   * @param type {@code MANUAL_DECREASE} or {@code DAMAGE}; {@code MANUAL_DECREASE} when null
   */
  @Transactional
  public StockAdjustmentResult adjust(
      String positionId, int quantity, StockAdjustmentType type, String reason) {
    StockAdjustmentType effectiveType = type == null ? StockAdjustmentType.MANUAL_DECREASE : type;
    if (effectiveType != StockAdjustmentType.MANUAL_DECREASE
        && effectiveType != StockAdjustmentType.DAMAGE) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ADJUSTMENT_TYPE");
    }
    if (quantity <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }
    Position position =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new PositionNotFoundException(positionId));
    if (position.getProductId() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "POSITION_WITHOUT_PRODUCT");
    }
    int before = position.getCurrentStock();
    if (quantity > before) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_POSITION_STOCK");
    }
    position.setCurrentStock(before - quantity);
    positionRepository.save(position);

    // An inactive position's stock never counted as available, so orders do not depend on it.
    String reconcileProductId = position.isActive() ? position.getProductId() : null;
    return recordChange(
        new StockChange(
            positionId,
            position.getProductId(),
            effectiveType,
            before,
            before - quantity,
            reconcileProductId,
            reason));
  }

  /**
   * Records a change already applied to a position and, if it took available stock away, cancels
   * the product's orders that no longer fit. Runs in the caller's transaction when there is one.
   */
  @Transactional
  public StockAdjustmentResult recordChange(StockChange change) {
    List<Order> cancelled =
        change.reconcileProductId() == null ? List.of() : reconcile(change.reconcileProductId());

    String reason = change.reason() == null || change.reason().isBlank() ? null : change.reason();
    StockAdjustment saved =
        adjustmentRepository.save(
            new StockAdjustment(
                null,
                change.positionId(),
                change.productId(),
                change.type(),
                reason,
                change.quantityBefore(),
                change.quantityAfter(),
                currentActor(),
                Instant.now(),
                cancelled.stream().map(Order::getId).toList()));
    if (!cancelled.isEmpty()) {
      publishAfterCommit(cancelled);
    }
    return new StockAdjustmentResult(saved, cancelled);
  }

  public Page<StockAdjustment> getPositionHistory(String positionId, Pageable pageable) {
    if (!positionRepository.existsById(positionId)) {
      throw new PositionNotFoundException(positionId);
    }
    return adjustmentRepository.findByPositionIdOrderByCreatedAtDesc(positionId, pageable);
  }

  public Page<StockAdjustment> getProductHistory(String productId, Pageable pageable) {
    if (!productRepository.existsById(productId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    return adjustmentRepository.findByProductIdOrderByCreatedAtDesc(productId, pageable);
  }

  private List<Order> reconcile(String productId) {
    orderRepository.guardStockReconciliation(productId);
    int physicalStock =
        positionRepository.findByProductIdInAndIsActiveTrue(List.of(productId)).stream()
            .mapToInt(Position::getCurrentStock)
            .sum();
    return OrderReconciler.ordersToCancel(
            productId, physicalStock, orderRepository.findActiveByProductId(productId))
        .stream()
        .map(c -> orderRepository.cancelWithoutPublishing(c.order(), c.reason()))
        .toList();
  }

  private void publishAfterCommit(List<Order> cancelled) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publish(cancelled);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publish(cancelled);
          }
        });
  }

  /** The change is already committed here, so a failure to announce it must not fail the call. */
  private void publish(List<Order> cancelled) {
    for (Order order : cancelled) {
      try {
        orderRepository.publishCancelled(order);
        orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(order));
      } catch (RuntimeException e) {
        log.error("could not announce cancelled order {}", order.getId(), e);
      }
    }
  }

  private static String currentActor() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || authentication.getName() == null
        ? SYSTEM_ACTOR
        : authentication.getName();
  }
}
