package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.service.OrderEventPublisher;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderStatusConsumerTest {

  @Mock OrderMongoRepository orderMongoRepository;
  @Mock OrderEventPublisher orderEventPublisher;
  OrderStatusConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new OrderStatusConsumer(orderMongoRepository, List.of(orderEventPublisher));
  }

  @Test
  void consume_inProgress_updatesOrderAndBroadcasts() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String message = new ObjectMapper().writeValueAsString(
        new OrderStatusMessage("order.status", "ord-1", "vhc-1", "in_progress", "2026-05-01T10:00:00Z"));

    consumer.consume(message);

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.IN_PROGRESS, captor.getValue().getStatus());
    assertEquals("vhc-1", captor.getValue().getAssignedVehicleId());
    verify(orderEventPublisher).broadcastOrderUpdate(any(Order.class));
  }

  @Test
  void consume_completed_setsCompletedTimestamp() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String message = new ObjectMapper().writeValueAsString(
        new OrderStatusMessage("order.status", "ord-1", "vhc-1", "completed", "2026-05-01T10:05:00Z"));

    consumer.consume(message);

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.COMPLETED, captor.getValue().getStatus());
  }
}
