package com.usal.whbackend.repository.kafka;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.service.OrderEventPublisher;
import com.usal.whbackend.service.StockDrainPort;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderStatusConsumer {

  private static final Logger log = LoggerFactory.getLogger(OrderStatusConsumer.class);
  private final OrderMongoRepository orderMongoRepository;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final StockDrainPort stockDrainPort;
  private final ObjectMapper objectMapper;

  public OrderStatusConsumer(
      OrderMongoRepository orderMongoRepository,
      List<OrderEventPublisher> orderEventPublishers,
      StockDrainPort stockDrainPort,
      ObjectMapper objectMapper) {
    this.orderMongoRepository = orderMongoRepository;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
    this.stockDrainPort = stockDrainPort;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(topics = "order.status", groupId = "wh-backend")
  public void consume(String payload) {
    OrderStatusMessage msg;
    try {
      msg = objectMapper.readValue(payload, OrderStatusMessage.class);
    } catch (Exception e) {
      // Malformed JSON cannot be retried — discard and log.
      log.error("Unparseable order.status message — discarding: {}", e.getMessage());
      return;
    }

    // All remaining exceptions propagate so Spring Kafka can retry / dead-letter.
    orderMongoRepository
        .findById(msg.orderId())
        .ifPresent(
            order -> {
              applyStatus(order, msg);
              Order saved = orderMongoRepository.save(order);
              orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
            });
  }

  private void applyStatus(Order order, OrderStatusMessage msg) {
    switch (msg.status()) {
      case "in_progress" -> {
        order.setStatus(OrderStatus.IN_PROGRESS);
        order.setAssignedVehicleId(msg.vehicleId());
        order.setStartedAt(parseTimestamp(msg.timestamp()));
      }
      case "completed" -> {
        order.setStatus(OrderStatus.COMPLETED);
        order.setCompletedAt(parseTimestamp(msg.timestamp()));
        stockDrainPort.drain(order.getItems());
      }
      case "cancelled" -> order.setStatus(OrderStatus.CANCELLED);
      default -> {}
    }
  }

  /** Parses an ISO-8601 timestamp, falling back to {@link Instant#now()} when null. */
  private Instant parseTimestamp(String timestamp) {
    if (timestamp == null) {
      log.warn("order.status message arrived with null timestamp — using current time");
      return Instant.now();
    }
    return Instant.parse(timestamp);
  }
}
