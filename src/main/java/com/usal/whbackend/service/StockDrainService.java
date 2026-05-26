package com.usal.whbackend.service;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
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
      int remaining = item.getQuantity();

      List<Position> positions =
          positionRepository
              .findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
                  item.getProductId(), 0);

      for (Position position : positions) {
        if (remaining <= 0) break;
        int drained = Math.min(remaining, position.getCurrentStock());
        position.setCurrentStock(position.getCurrentStock() - drained);
        positionRepository.save(position);
        remaining -= drained;
      }

      int totalStock =
          positionRepository
              .findByProductIdInAndIsActiveTrue(List.of(item.getProductId()))
              .stream()
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
}
