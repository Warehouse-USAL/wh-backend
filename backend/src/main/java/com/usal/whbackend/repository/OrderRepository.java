package com.usal.whbackend.repository;

import com.mongodb.MongoException;
import com.usal.whbackend.config.RequestIdFilter;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCompletedMessage;
import com.usal.whbackend.repository.kafka.OrderMessage;
import com.usal.whbackend.telemetry.TelemetryPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class OrderRepository {

  private static final Logger log = LoggerFactory.getLogger(OrderRepository.class);

  /**
   * One document per product, bumped by every stock reconciliation and every order creation to
   * serialize them.
   */
  private static final String STOCK_GUARDS = "stock_guards";

  /** Carries the request id to the bridge, so one id follows an order across the services. */
  static final String REQUEST_ID_HEADER = "x-request-id";

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
  private final TelemetryPort telemetry;

  public OrderRepository(
      OrderMongoRepository mongo,
      KafkaTemplate<String, String> kafka,
      MongoTemplate mongoTemplate,
      ObjectMapper objectMapper,
      TelemetryPort telemetry) {
    this.mongo = mongo;
    this.kafka = kafka;
    this.mongoTemplate = mongoTemplate;
    this.objectMapper = objectMapper;
    this.telemetry = telemetry;
  }

  public Order save(Order order) {
    Order saved = mongo.save(order);
    publishPendingOrder(saved);
    return saved;
  }

  /**
   * Persists a new order without publishing it, so callers can publish after their transaction
   * commits.
   */
  public Order saveWithoutPublishing(Order order) {
    return mongo.save(order);
  }

  /** Publishes a newly created order to Infra after it has been committed. */
  public void publishPendingOrder(Order order) {
    publishOrder(order, "pending");
  }

  public Order update(Order order) {
    return mongo.save(order);
  }

  public Order cancel(Order order, String reason) {
    Order saved = cancelWithoutPublishing(order, reason);
    publishCancelled(saved);
    return saved;
  }

  /**
   * Cancels an order without telling Infra, so callers can publish after their transaction commits.
   */
  public Order cancelWithoutPublishing(Order order, String reason) {
    order.setCancelReason(reason);
    order.setStatus(OrderStatus.CANCELLED);
    return mongo.save(order);
  }

  /** Tells Infra, on the {@code orders} topic, that an order was cancelled. */
  public void publishCancelled(Order order) {
    publishOrder(order, "cancelled");
  }

  /**
   * Makes concurrent stock reconciliations of the same product conflict. Inside a MongoDB
   * transaction reads come from a snapshot and only writes to the same document collide, so two
   * adjustments of different positions of one product would each miss the other's drop and keep
   * orders the remaining stock cannot cover. Both bump this product's guard document: the second to
   * commit aborts with a transient write conflict instead of reconciling against a stale view.
   * Order creation bumps the same document before it checks availability, so an order and an
   * adjustment of one product cannot both commit on the same reading either.
   */
  public void guardStockReconciliation(String productId) {
    try {
      mongoTemplate.upsert(
          Query.query(Criteria.where("_id").is(productId)),
          new Update().inc("version", 1),
          STOCK_GUARDS);
    } catch (DataAccessException e) {
      if (isTransientTransactionError(e)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "STOCK_CHANGE_CONFLICT", e);
      }
      throw e;
    }
  }

  private static boolean isTransientTransactionError(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof MongoException m
          && m.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)) {
        return true;
      }
    }
    return false;
  }

  /** Pending and in-progress orders that include {@code productId} — the ones holding its stock. */
  public List<Order> findActiveByProductId(String productId) {
    Query query =
        Query.query(
            Criteria.where("status")
                .in(OrderStatus.PENDING, OrderStatus.IN_PROGRESS)
                .and("items.productId")
                .is(productId));
    return mongoTemplate.find(query, Order.class);
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
      // The MDC is per thread: read the id here, on the request thread, not on the publisher.
      String requestId = MDC.get(RequestIdFilter.MDC_KEY);
      CompletableFuture.runAsync(
              () -> {
                long start = System.nanoTime();
                kafka
                    .send(record(topic, key, json, requestId))
                    .whenComplete(
                        (result, ex) -> {
                          double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
                          telemetry.recordKafkaPublish(topic, ex == null, elapsedMs);
                          telemetry.recordKafkaMessage(topic, "out", ex == null);
                          long ms = (long) elapsedMs;
                          withRequestId(
                              requestId,
                              () -> {
                                if (ex == null) {
                                  log.info("kafka publish {} ok {}ms", topic, ms);
                                } else {
                                  log.error(
                                      "kafka publish {} failed {}ms: {}",
                                      topic,
                                      ms,
                                      ex.getMessage());
                                }
                              });
                        });
              },
              KAFKA_PUBLISHER)
          // A throw from send() itself (e.g. the producer cannot be built) would otherwise vanish
          // inside the discarded future.
          .exceptionally(
              ex -> {
                telemetry.recordKafkaMessage(topic, "out", false);
                withRequestId(requestId, () -> log.error("kafka publish {} failed", topic, ex));
                return null;
              });
    } catch (JacksonException e) {
      throw new RuntimeException("Failed to serialize Kafka message for topic " + topic, e);
    }
  }

  /**
   * Logs on the publisher or producer thread under the request id captured on the request thread,
   * so the line joins that request's trail.
   */
  private static void withRequestId(String requestId, Runnable line) {
    if (!RequestIdFilter.isSafe(requestId)) {
      line.run();
      return;
    }
    MDC.put(RequestIdFilter.MDC_KEY, requestId);
    try {
      line.run();
    } finally {
      MDC.remove(RequestIdFilter.MDC_KEY);
    }
  }

  /** Same record {@code send(topic, [key,] value)} builds, plus the request id when it is safe. */
  private static ProducerRecord<String, String> record(
      String topic, String key, String json, String requestId) {
    ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, json);
    if (RequestIdFilter.isSafe(requestId)) {
      record.headers().add(REQUEST_ID_HEADER, requestId.getBytes(StandardCharsets.UTF_8));
    }
    return record;
  }
}
