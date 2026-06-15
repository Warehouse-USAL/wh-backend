package com.usal.whbackend.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OrderRepositoryTest {

  @Mock OrderMongoRepository mongo;
  @Mock KafkaTemplate<String, String> kafka;
  @Mock MongoTemplate mongoTemplate;
  OrderRepository orderRepository;

  @BeforeEach
  void setUp() {
    orderRepository = new OrderRepository(mongo, kafka, mongoTemplate, new ObjectMapper());
    @SuppressWarnings("unchecked")
    CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
    lenient().when(kafka.send(anyString(), anyString())).thenReturn(future);
  }

  @Test
  void save_persistsToMongoAndPublishesDispatchToKafka() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of(new OrderItem("prod-1", "SKU-001", 2)));
    order.setDestinationArea("AREA-A");
    when(mongo.save(order)).thenReturn(order);

    orderRepository.save(order);

    verify(mongo).save(order);
    // Kafka publish is async — wait briefly for the background thread to fire
    Thread.sleep(100);
    verify(kafka).send(eq("order.dispatch"), anyString());
  }

  @Test
  void cancel_persistsToMongoAndPublishesCancelToKafka() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    order.setItems(List.of());
    when(mongo.save(order)).thenReturn(order);

    orderRepository.cancel(order, "Cancelado por el usuario");

    verify(mongo).save(order);
    // Kafka publish is async — wait briefly for the background thread to fire
    Thread.sleep(100);
    verify(kafka).send(eq("order.cancel"), anyString());
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
}
