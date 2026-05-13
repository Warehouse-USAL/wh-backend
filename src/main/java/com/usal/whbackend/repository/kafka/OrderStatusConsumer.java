package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.service.OrderEventPublisher;
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
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OrderStatusConsumer(
      OrderMongoRepository orderMongoRepository,
      List<OrderEventPublisher> orderEventPublishers) {
    this.orderMongoRepository = orderMongoRepository;
    this.orderEventPublishers = orderEventPublishers;
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
      }
      case "cancelled" -> {
        order.setStatus(OrderStatus.CANCELLED);
      }
      default -> {}
    }
  }
}
