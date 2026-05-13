package com.usal.whbackend.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCancelMessage;
import com.usal.whbackend.repository.kafka.OrderDispatchMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

  private final OrderMongoRepository mongo;
  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OrderRepository(OrderMongoRepository mongo, KafkaTemplate<String, String> kafka) {
    this.mongo = mongo;
    this.kafka = kafka;
  }

  public Order save(Order order) {
    Order saved = mongo.save(order);
    publishDispatch(saved);
    return saved;
  }

  public Order cancel(Order order, String reason) {
    order.setCancelReason(reason);
    order.setStatus(OrderStatus.CANCELLED);
    Order saved = mongo.save(order);
    publishCancel(saved, reason);
    return saved;
  }

  public Optional<Order> findById(String id) {
    return mongo.findById(id);
  }

  public List<Order> findAll() {
    return mongo.findAll();
  }

  public List<Order> findByStatus(OrderStatus status) {
    return mongo.findByStatus(status);
  }

  public List<Order> findByAssignedVehicleId(String vehicleId) {
    return mongo.findByAssignedVehicleId(vehicleId);
  }

  public List<Order> findByRequestedByUserId(String userId) {
    return mongo.findByRequestedByUserId(userId);
  }

  private void publishDispatch(Order order) {
    List<OrderDispatchMessage.Item> items =
        order.getItems() == null
            ? List.of()
            : order.getItems().stream()
                .map(i -> new OrderDispatchMessage.Item(i.getProductId(), i.getSku(), i.getQuantity()))
                .toList();

    OrderDispatchMessage msg =
        new OrderDispatchMessage(
            "order.dispatch",
            order.getId(),
            items,
            order.getDestinationArea(),
            Instant.now().toString());
    send("order.dispatch", msg);
  }

  private void publishCancel(Order order, String reason) {
    OrderCancelMessage msg =
        new OrderCancelMessage("order.cancel", order.getId(), reason, Instant.now().toString());
    send("order.cancel", msg);
  }

  private void send(String topic, Object payload) {
    try {
      kafka.send(topic, objectMapper.writeValueAsString(payload))
          .whenComplete(
              (result, ex) -> {
                if (ex != null) {
                  log.error("Failed to publish to Kafka topic {}: {}", topic, ex.getMessage());
                }
              });
    } catch (JsonProcessingException e) {
      throw new RuntimeException("Failed to serialize Kafka message for topic " + topic, e);
    }
  }
}
