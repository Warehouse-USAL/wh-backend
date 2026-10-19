package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCompletedMessage;
import com.usal.whbackend.repository.kafka.OrderMessage;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

  /**
   * Publishes off the request thread (send() blocks while the broker is unreachable) on a dedicated
   * thread rather than the common pool: pool threads carry the system classloader, and Kafka then
   * cannot load its own config classes ({@code DefaultJwtRetriever}) from the Spring Boot fat jar.
   * Being single-threaded also keeps a cancel behind the create it follows.
   */
  private static final Executor KAFKA_PUBLISHER =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "kafka-order-publisher");
            t.setDaemon(true);
            return t;
          });

  private final OrderMongoRepository mongo;
  private final KafkaTemplate<String, String> kafka;
  private final MongoTemplate mongoTemplate;
  private final ObjectMapper objectMapper;

  public OrderRepository(
      OrderMongoRepository mongo,
      KafkaTemplate<String, String> kafka,
      MongoTemplate mongoTemplate,
      ObjectMapper objectMapper) {
    this.mongo = mongo;
    this.kafka = kafka;
    this.mongoTemplate = mongoTemplate;
    this.objectMapper = objectMapper;
  }

  public Order save(Order order) {
    Order saved = mongo.save(order);
    publishOrder(saved, "pending");
    return saved;
  }

  public Order update(Order order) {
    return mongo.save(order);
  }

  public Order cancel(Order order, String reason) {
    order.setCancelReason(reason);
    order.setStatus(OrderStatus.CANCELLED);
    Order saved = mongo.save(order);
    publishOrder(saved, "cancelled");
    return saved;
  }

  /**
   * Asks Infra to cancel an order a vehicle is already working on. The order keeps its status: it
   * only becomes CANCELLED when Infra reports it, once the vehicle has put the goods back.
   */
  public Order requestCancellation(Order order, String reason) {
    order.setCancelReason(reason);
    order.setCancellationRequestedAt(Instant.now());
    Order saved = mongo.save(order);
    publishOrder(saved, "cancelled");
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

  /** Publishes the order and its stops on the {@code orders} topic, keyed by order id. */
  private void publishOrder(Order order, String status) {
    OrderMessage msg =
        new OrderMessage(
            order.getId(),
            status,
            order.getStops(),
            order.getDestinationArea(),
            Instant.now().toString());
    sendAsync("orders", order.getId(), msg);
  }

  /** Publishes the {@code order.completed} fact consumed by {@code wh-suggestions}. */
  public void publishCompleted(Order order) {
    List<OrderCompletedMessage.Item> items =
        order.getItems() == null
            ? List.of()
            : order.getItems().stream()
                .map(
                    i ->
                        new OrderCompletedMessage.Item(
                            i.getSku(), i.getProductId(), i.getQuantity()))
                .toList();
    OrderCompletedMessage msg =
        new OrderCompletedMessage(
            order.getId(),
            order.getRequestedByUserId(),
            order.getDestinationArea(),
            items,
            order.getCompletedAt() == null ? null : order.getCompletedAt().toString());
    sendAsync("order.completed", null, msg);
  }

  private void sendAsync(String topic, String key, Object payload) {
    try {
      String json = objectMapper.writeValueAsString(payload);
      CompletableFuture.runAsync(
              () ->
                  (key == null ? kafka.send(topic, json) : kafka.send(topic, key, json))
                      .whenComplete(
                          (result, ex) -> {
                            if (ex != null) {
                              log.error(
                                  "Failed to publish to Kafka topic {}: {}",
                                  topic,
                                  ex.getMessage());
                            }
                          }),
              KAFKA_PUBLISHER)
          // A throw from send() itself (e.g. the producer cannot be built) would otherwise vanish
          // inside the discarded future.
          .exceptionally(
              ex -> {
                log.error("Failed to publish to Kafka topic {}", topic, ex);
                return null;
              });
    } catch (JacksonException e) {
      throw new RuntimeException("Failed to serialize Kafka message for topic " + topic, e);
    }
  }
}
