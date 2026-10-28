package com.usal.whbackend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Address;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OrderRepositoryTest {

  @Mock OrderMongoRepository mongo;
  @Mock KafkaTemplate<String, String> kafka;
  @Mock MongoTemplate mongoTemplate;
  OrderRepository orderRepository;

  @BeforeEach
  void setUp() {
    orderRepository =
        new OrderRepository(
            mongo, kafka, mongoTemplate, new ObjectMapper(), new NoOpTelemetryAdapter());
    @SuppressWarnings("unchecked")
    CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
    lenient().when(kafka.send(any(ProducerRecord.class))).thenReturn(future);
  }

  /** The single record published, checked to go to {@code topic} under {@code key}. */
  @SuppressWarnings("unchecked")
  private String sentValue(String topic, String key) {
    ArgumentCaptor<ProducerRecord<String, String>> record =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafka).send(record.capture());
    assertEquals(topic, record.getValue().topic());
    assertEquals(key, record.getValue().key());
    return record.getValue().value();
  }

  @Test
  void save_persistsToMongoAndPublishesPendingOrderKeyedByIdToKafka() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of(new OrderItem("prod-1", "SKU-001", 2)));
    order.setDestinationArea("AREA-A");
    order.setStops(List.of("WH|A-L1-P01", "WH|A-L1-P02"));
    when(mongo.save(order)).thenReturn(order);

    orderRepository.save(order);

    verify(mongo).save(order);
    // Kafka publish is async — wait briefly for the background thread to fire
    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("orders", "ord-1"));
    assertEquals("ord-1", node.get("order_id").asString());
    assertEquals("pending", node.get("status").asString());
    assertEquals("AREA-A", node.get("destination").asString());
    assertEquals("WH|A-L1-P01", node.get("stops").get(0).asString());
    assertEquals("WH|A-L1-P02", node.get("stops").get(1).asString());
    assertTrue(node.has("published_at"));
  }

  @Test
  void publishCompleted_sendsCamelCasePayloadToOrderCompletedTopic() throws Exception {
    Order order = new Order();
    order.setId("o-0421");
    order.setRequestedByUserId("u-operator-3");
    order.setDestinationArea("Sucursal Pocitos");
    order.setCompletedAt(Instant.parse("2026-08-30T14:22:05Z"));
    order.setItems(
        List.of(
            new OrderItem("p-TEC-002", "TEC-002", 2), new OrderItem("p-TEC-004", "TEC-004", 1)));

    orderRepository.publishCompleted(order);

    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("order.completed", null));
    assertEquals("o-0421", node.get("orderId").asString());
    assertEquals("u-operator-3", node.get("userId").asString());
    assertEquals("Sucursal Pocitos", node.get("destinationArea").asString());
    assertEquals("2026-08-30T14:22:05Z", node.get("completedAt").asString());
    assertEquals(2, node.get("items").size());
    assertEquals("TEC-002", node.get("items").get(0).get("sku").asString());
    assertEquals("p-TEC-002", node.get("items").get(0).get("productId").asString());
    assertEquals(2, node.get("items").get(0).get("quantity").asInt());
  }

  @Test
  void publishCompleted_flagsTheItemsTakenFromARecommendation() throws Exception {
    Order order = new Order();
    order.setId("o-1");
    order.setItems(
        List.of(
            new OrderItem("p-1", "TEC-001", 1, null, true),
            new OrderItem("p-2", "TEC-002", 1, null, false),
            new OrderItem("p-3", "TEC-003", 1)));

    orderRepository.publishCompleted(order);

    Thread.sleep(100);
    JsonNode items = new ObjectMapper().readTree(sentValue("order.completed", null)).get("items");
    assertTrue(items.get(0).get("fromRecommendation").asBoolean());
    // Omitted (not false) for the other lines: their payload is what it was before the field.
    assertFalse(items.get(1).has("fromRecommendation"));
    assertFalse(items.get(2).has("fromRecommendation"));
  }

  @Test
  void publishCompleted_withNullItemsAndCompletedAt_publishesEmptyItemsAndNull() throws Exception {
    Order order = new Order();
    order.setId("o-1");
    order.setItems(null);

    orderRepository.publishCompleted(order);

    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("order.completed", null));
    assertEquals(0, node.get("items").size());
    assertTrue(node.get("completedAt").isNull());
  }

  @Test
  void cancel_persistsToMongoAndPublishesTheCancelledOrderWithItsStops() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    order.setItems(List.of());
    order.setStops(List.of("WH|A-L1-P01"));
    when(mongo.save(order)).thenReturn(order);

    orderRepository.cancel(order, "Cancelado por el usuario");

    verify(mongo).save(order);
    // Kafka publish is async — wait briefly for the background thread to fire
    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("orders", "ord-1"));
    assertEquals("cancelled", node.get("status").asString());
    assertEquals("WH|A-L1-P01", node.get("stops").get(0).asString());
  }

  @Test
  void findByFilters_noFilters_returnsAllOrders() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o1 = new Order();
    o1.setId("ord-1");
    Order o2 = new Order();
    o2.setId("ord-2");
    when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(2L);
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(o1, o2));

    Page<Order> result = orderRepository.findByFilters(null, null, null, null, pageable);

    assertEquals(2, result.getContent().size());
    assertEquals(2L, result.getTotalElements());
  }

  @Test
  void findByFilters_withoutASort_listsNewestFirst() {
    when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of());

    orderRepository.findByFilters(null, null, null, null, PageRequest.of(2, 10));

    org.mockito.ArgumentCaptor<Query> query = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Order.class));
    assertEquals(-1, query.getValue().getSortObject().get("createdAt"));
    assertEquals(20, query.getValue().getSkip());
    assertEquals(10, query.getValue().getLimit());
  }

  @Test
  void findByFilters_withACallerSort_keepsIt() {
    when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of());

    orderRepository.findByFilters(
        null,
        null,
        null,
        null,
        PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("status")));

    org.mockito.ArgumentCaptor<Query> query = org.mockito.ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Order.class));
    assertEquals(1, query.getValue().getSortObject().get("status"));
    assertFalse(query.getValue().getSortObject().containsKey("createdAt"));
  }

  @Test
  void findByFilters_withStatusAndVehicle_queriesMongoTemplate() {
    Pageable pageable = PageRequest.of(0, 10);
    Order o = new Order();
    o.setStatus(OrderStatus.PENDING);
    when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(o));

    Page<Order> result =
        orderRepository.findByFilters(OrderStatus.PENDING, "veh-1", null, null, pageable);

    assertEquals(1, result.getContent().size());
    verify(mongoTemplate).find(any(Query.class), eq(Order.class));
  }

  @Test
  void findByFilters_withFromAndTo_doesNotThrowDuplicateFieldException() {
    // Regression: adding two criteria on "createdAt" (gte + lte) separately would throw
    // InvalidMongoDbApiUsageException. They must be combined into one Criteria chain.
    Pageable pageable = PageRequest.of(0, 10);
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-12-31T23:59:59Z");
    when(mongoTemplate.count(any(Query.class), eq(Order.class))).thenReturn(0L);
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of());

    // Must not throw
    Page<Order> result = orderRepository.findByFilters(null, null, from, to, pageable);

    assertEquals(0, result.getTotalElements());
    verify(mongoTemplate).find(any(Query.class), eq(Order.class));
  }

  @Test
  void update_savesWithoutPublishingToKafka() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    when(mongo.save(order)).thenReturn(order);

    assertSame(order, orderRepository.update(order));

    Thread.sleep(50);
    verify(kafka, org.mockito.Mockito.never()).send(any(ProducerRecord.class));
  }

  @Test
  void findById_delegatesToMongo() {
    Order order = new Order();
    order.setId("ord-1");
    when(mongo.findById("ord-1")).thenReturn(java.util.Optional.of(order));

    assertEquals("ord-1", orderRepository.findById("ord-1").orElseThrow().getId());
  }

  @Test
  void findByRequestedByUserId_delegatesToMongo() {
    Order order = new Order();
    when(mongo.findByRequestedByUserId("usr-1")).thenReturn(List.of(order));

    assertEquals(1, orderRepository.findByRequestedByUserId("usr-1").size());
  }

  @Test
  void save_neverPutsItemsAddressOrVehicleOnTheWire() throws Exception {
    Address address = new Address();
    address.setStreet("Calle 1");
    address.setPostalCode("1000");
    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of(new OrderItem("prod-1", "SKU-001", 2)));
    order.setAddress(address);
    order.setAssignedVehicleId("vhc-1");
    when(mongo.save(order)).thenReturn(order);

    orderRepository.save(order);

    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("orders", "ord-1"));
    assertEquals(
        java.util.Set.of("order_id", "status", "stops", "destination", "published_at"),
        new java.util.HashSet<>(node.propertyNames()));
  }

  @Test
  void save_withNoStops_publishesAnEmptyStopList() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    when(mongo.save(order)).thenReturn(order);

    orderRepository.save(order);

    Thread.sleep(100);
    assertTrue(sentValue("orders", "ord-1").contains("\"stops\":[]"));
  }

  @Test
  void save_kafkaFailure_isLoggedAndDoesNotPropagate() throws Exception {
    CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
    failed.completeExceptionally(new IllegalStateException("broker down"));
    when(kafka.send(any(ProducerRecord.class))).thenReturn(failed);

    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of());
    when(mongo.save(order)).thenReturn(order);

    orderRepository.save(order);

    Thread.sleep(100);
    sentValue("orders", "ord-1");
  }

  @Test
  void save_unserialisablePayload_throwsRuntimeException() {
    ObjectMapper failing = org.mockito.Mockito.mock(ObjectMapper.class);
    when(failing.writeValueAsString(any()))
        .thenThrow(
            new tools.jackson.databind.exc.InvalidDefinitionException(
                (tools.jackson.core.JsonGenerator) null, "boom", null) {});
    OrderRepository repo =
        new OrderRepository(mongo, kafka, mongoTemplate, failing, new NoOpTelemetryAdapter());
    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of());
    when(mongo.save(order)).thenReturn(order);

    assertThrows(RuntimeException.class, () -> repo.save(order));
  }

  @Test
  void cancelWithoutPublishing_persistsTheCancellationAndSendsNothing() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(mongo.save(order)).thenReturn(order);

    Order saved = orderRepository.cancelWithoutPublishing(order, "INSUFFICIENT_STOCK: x");

    assertEquals(OrderStatus.CANCELLED, saved.getStatus());
    assertEquals("INSUFFICIENT_STOCK: x", saved.getCancelReason());
    Thread.sleep(100);
    org.mockito.Mockito.verifyNoInteractions(kafka);
  }

  @Test
  void publishCancelled_sendsTheCancelledMessageKeyedByOrderId() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStops(List.of("WH|A-L1-P01"));

    orderRepository.publishCancelled(order);

    Thread.sleep(100);
    JsonNode node = new ObjectMapper().readTree(sentValue("orders", "ord-1"));
    assertEquals("cancelled", node.get("status").asString());
  }

  @Test
  void findActiveByProductId_queriesPendingAndInProgressOrdersWithTheProduct() {
    Order order = new Order();
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of(order));

    assertEquals(List.of(order), orderRepository.findActiveByProductId("prod-1"));

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Order.class));
    org.bson.Document criteria = query.getValue().getQueryObject();
    assertEquals("prod-1", criteria.get("items.productId"));
    assertEquals(
        List.of(OrderStatus.PENDING, OrderStatus.IN_PROGRESS),
        ((org.bson.Document) criteria.get("status")).get("$in"));
  }

  @Test
  void guardStockReconciliation_bumpsTheProductsGuardDocument() {
    orderRepository.guardStockReconciliation("prod-1");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate)
        .upsert(
            query.capture(),
            any(org.springframework.data.mongodb.core.query.Update.class),
            eq("stock_guards"));
    assertEquals("prod-1", query.getValue().getQueryObject().get("_id"));
  }

  @Test
  void guardStockReconciliation_transientTransactionError_isAConflict() {
    com.mongodb.MongoException conflict = new com.mongodb.MongoException("write conflict");
    conflict.addLabel(com.mongodb.MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL);
    when(mongoTemplate.upsert(
            any(Query.class),
            any(org.springframework.data.mongodb.core.query.Update.class),
            eq("stock_guards")))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("112", conflict));

    org.springframework.web.server.ResponseStatusException e =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> orderRepository.guardStockReconciliation("prod-1"));

    assertEquals(org.springframework.http.HttpStatus.CONFLICT, e.getStatusCode());
    assertEquals("STOCK_CHANGE_CONFLICT", e.getReason());
  }

  @Test
  void guardStockReconciliation_otherDataErrors_propagateUnchanged() {
    org.springframework.dao.DataAccessResourceFailureException down =
        new org.springframework.dao.DataAccessResourceFailureException("down");
    when(mongoTemplate.upsert(
            any(Query.class),
            any(org.springframework.data.mongodb.core.query.Update.class),
            eq("stock_guards")))
        .thenThrow(down);

    assertSame(
        down,
        assertThrows(
            org.springframework.dao.DataAccessResourceFailureException.class,
            () -> orderRepository.guardStockReconciliation("prod-1")));
  }
}
