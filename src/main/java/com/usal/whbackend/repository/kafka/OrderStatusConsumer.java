package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.OrderEventPublisher;
import com.usal.whbackend.service.StockEventPublisher;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class OrderStatusConsumer {

  private static final Logger log = LoggerFactory.getLogger(OrderStatusConsumer.class);
  private final OrderMongoRepository orderMongoRepository;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final PositionRepository positionRepository;
  private final ProductRepository productRepository;
  private final List<StockEventPublisher> stockEventPublishers;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OrderStatusConsumer(
      OrderMongoRepository orderMongoRepository,
      List<OrderEventPublisher> orderEventPublishers,
      PositionRepository positionRepository,
      ProductRepository productRepository,
      List<StockEventPublisher> stockEventPublishers) {
    this.orderMongoRepository = orderMongoRepository;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
    this.positionRepository = positionRepository;
    this.productRepository = productRepository;
    this.stockEventPublishers = List.copyOf(stockEventPublishers);
  }

  @KafkaListener(topics = "order.status", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      OrderStatusMessage msg = objectMapper.readValue(payload, OrderStatusMessage.class);
      orderMongoRepository
          .findById(msg.orderId())
          .ifPresent(
              order -> {
                applyStatus(order, msg);
                Order saved = orderMongoRepository.save(order);
                orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
              });
    } catch (Exception e) {
      log.warn("Failed to process order.status message: {}", e.getMessage());
    }
  }

  private void applyStatus(Order order, OrderStatusMessage msg) {
    switch (msg.status()) {
      case "in_progress" -> {
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAssignedVehicleId(msg.vehicleId());
        order.setStartedAt(Instant.parse(msg.timestamp()));
      }
      case "completed" -> {
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(Instant.parse(msg.timestamp()));
        drainPositionStock(order);
      }
      case "cancelled" -> {
        order.setStatus(OrderStatus.CANCELLED);
      }
      default -> {}
    }
  }

  private void drainPositionStock(Order order) {
    if (order.getItems() == null) return;
    for (OrderItem item : order.getItems()) {
      int remaining = item.getQuantity();
      List<Position> positions =
          positionRepository.findByProductIdAndCurrentStockGreaterThanOrderByCreatedAtAsc(
              item.getProductId(), 0);
      for (Position position : positions) {
        if (remaining <= 0) break;
        int drain = Math.min(remaining, position.getCurrentStock());
        position.setCurrentStock(position.getCurrentStock() - drain);
        positionRepository.save(position);
        remaining -= drain;
      }
      int totalStock =
          positionRepository.findByProductIdIn(List.of(item.getProductId())).stream()
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
