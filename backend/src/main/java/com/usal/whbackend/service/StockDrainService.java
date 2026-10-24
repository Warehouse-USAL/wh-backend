package com.usal.whbackend.service;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drains warehouse position stock when an order is completed (FIFO by creation date). Extracted to
 * its own bean so {@code @Transactional} is honoured by the Spring AOP proxy — self-calls from
 * within the same class bypass the proxy and silently drop the transaction boundary.
 */
@Service
public class StockDrainService implements StockDrainPort {

  private final PositionRepository positionRepository;
  private final ProductRepository productRepository;
  private final List<StockEventPublisher> stockEventPublishers;

  public StockDrainService(
      PositionRepository positionRepository,
      ProductRepository productRepository,
      List<StockEventPublisher> stockEventPublishers) {
    this.positionRepository = positionRepository;
    this.productRepository = productRepository;
    this.stockEventPublishers = List.copyOf(stockEventPublishers);
  }

  /**
   * Drains stock for each item in FIFO order (oldest positions first). Only active positions are
   * eligible. If stock is insufficient the drain completes with whatever is available — callers
   * should validate availability before dispatching the order.
   */
  @Transactional
  public void drain(List<OrderItem> items) {
    if (items == null) return;
    for (OrderItem item : items) {
      for (Allocation allocation : allocate(item)) {
        Position position = allocation.position();
        position.setCurrentStock(position.getCurrentStock() - allocation.quantity());
        positionRepository.save(position);
      }

      int totalStock =
          positionRepository.findByProductIdInAndIsActiveTrue(List.of(item.getProductId())).stream()
              .mapToInt(Position::getCurrentStock)
              .sum();

      productRepository
          .findById(item.getProductId())
          .ifPresent(
              product -> {
                if (totalStock < product.getMinimumStock()) {
                  stockEventPublishers.forEach(p -> p.broadcastStockAlert(product, totalStock));
                }
              });
    }
  }

  @Override
  public List<Position> positionsToConsume(List<OrderItem> items) {
    if (items == null) return List.of();
    List<Position> consumed = new ArrayList<>();
    for (OrderItem item : items) {
      allocate(item).forEach(a -> consumed.add(a.position()));
    }
    return consumed;
  }

  /** How much of {@code item} each position (oldest first) gives up; the single FIFO rule. */
  private List<Allocation> allocate(OrderItem item) {
    int remaining = item.getQuantity();
    List<Allocation> allocations = new ArrayList<>();
    List<Position> positions =
        positionRepository
            .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                item.getProductId(), 0);
    for (Position position : positions) {
      if (remaining <= 0) break;
      int taken = Math.min(remaining, position.getCurrentStock());
      allocations.add(new Allocation(position, taken));
      remaining -= taken;
    }
    return allocations;
  }

  private record Allocation(Position position, int quantity) {}
}
