package com.usal.whbackend.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCancelMessage;
import com.usal.whbackend.repository.kafka.OrderDispatchMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

  private final OrderMongoRepository mongo;
  private final KafkaTemplate<String, String> kafka;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public OrderRepository(
      OrderMongoRepository mongo,
      KafkaTemplate<String, String> kafka,
      MongoTemplate mongoTemplate) {
    this.mongo = mongo;
    this.kafka = kafka;
    this.mongoTemplate = mongoTemplate;
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

  public Page<Order> findByFilters(
      OrderStatus status, String vehicleId, Instant from, Instant to, Pageable pageable) {
    Query query = new Query();
    if (status != null) {
      query.addCriteria(Criteria.where("status").is(status));
    }
    if (vehicleId != null) {
      query.addCriteria(Criteria.where("assignedVehicleId").is(vehicleId));
    }
    // from and to must be combined into a single Criteria for the same field —
    // calling addCriteria() twice on "createdAt" throws InvalidMongoDbApiUsageException.
    if (from != null || to != null) {
      Criteria createdAtCriteria = Criteria.where("createdAt");
      if (from != null) {
        createdAtCriteria = createdAtCriteria.gte(from);
      }
      if (to != null) {
        createdAtCriteria = createdAtCriteria.lte(to);
      }
      query.addCriteria(createdAtCriteria);
    }
    long total = mongoTemplate.count(query, Order.class);
    List<Order> items = mongoTemplate.find(query.with(pageable), Order.class);
    return new PageImpl<>(items, pageable, total);
  }

  public List<Order> findByRequestedByUserId(String userId) {
    return mongo.findByRequestedByUserId(userId);
  }

  private void publishDispatch(Order order) {
    List<OrderDispatchMessage.Item> items =
        order.getItems() == null
            ? List.of()
            : order.getItems().stream()
                .map(
                    i ->
                        new OrderDispatchMessage.Item(
                            i.getProductId(), i.getSku(), i.getQuantity()))
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
      kafka
          .send(topic, objectMapper.writeValueAsString(payload))
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
