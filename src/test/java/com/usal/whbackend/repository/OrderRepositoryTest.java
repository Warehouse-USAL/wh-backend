package com.usal.whbackend.repository;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OrderRepositoryTest {

  @Mock OrderMongoRepository mongo;
  @Mock KafkaTemplate<String, String> kafka;
  OrderRepository orderRepository;

  @BeforeEach
  void setUp() {
    orderRepository = new OrderRepository(mongo, kafka);
    @SuppressWarnings("unchecked")
    CompletableFuture<SendResult<String, String>> future = CompletableFuture.completedFuture(null);
    when(kafka.send(anyString(), anyString())).thenReturn(future);
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
    verify(kafka).send(eq("order.cancel"), anyString());
  }
}
