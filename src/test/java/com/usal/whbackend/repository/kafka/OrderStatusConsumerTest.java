package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.service.OrderEventPublisher;
import com.usal.whbackend.service.StockDrainPort;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderStatusConsumerTest {

  @Mock OrderMongoRepository orderMongoRepository;
  @Mock OrderEventPublisher orderEventPublisher;
  @Mock StockDrainPort stockDrainPort;
  OrderStatusConsumer consumer;

  /** Shared ObjectMapper configured the same way as the production Spring bean. */
  private final ObjectMapper objectMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  @BeforeEach
  void setUp() {
    consumer =
        new OrderStatusConsumer(
            orderMongoRepository, List.of(orderEventPublisher), stockDrainPort, objectMapper);
  }

  // ── in_progress ────────────────────────────────────────────────────────────

  @Test
  void consume_inProgress_updatesOrderAndBroadcasts() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", "vhc-1", "in_progress", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.IN_PROGRESS, captor.getValue().getStatus());
    assertEquals("vhc-1", captor.getValue().getAssignedVehicleId());
    verify(orderEventPublisher).broadcastOrderUpdate(any(Order.class));
  }

  // ── completed ──────────────────────────────────────────────────────────────

  @Test
  void consume_completed_setsTimestampAndDrains() throws Exception {
    List<OrderItem> items = List.of(new OrderItem("prod-1", "SKU-1", 3));
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(items);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", "vhc-1", "completed", "2026-05-01T10:05:00Z"));

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.COMPLETED, captor.getValue().getStatus());
    // Drain must be called with the order's item list.
    verify(stockDrainPort).drain(items);
  }

  @Test
  void consume_completed_withNullItems_doesNotCallDrain() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(null);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", "vhc-1", "completed", "2026-05-01T10:05:00Z"));

    // drain() is still called — StockDrainPort impl guards against null items.
    verify(stockDrainPort).drain(null);
  }

  @Test
  void consume_completed_nullTimestamp_usesCurrentTime() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(List.of());
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Null timestamp must not throw — consumer falls back to Instant.now().
    String payload =
        "{\"order_id\":\"ord-1\",\"vehicle_id\":\"vhc-1\","
            + "\"status\":\"completed\",\"timestamp\":null}";
    consumer.consume(payload);

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.COMPLETED, captor.getValue().getStatus());
  }

  // ── cancelled ─────────────────────────────────────────────────────────────

  @Test
  void consume_cancelled_setsStatusAndNeverDrains() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", "vhc-1", "cancelled", "2026-05-01T10:05:00Z"));

    ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
    verify(orderMongoRepository).save(captor.capture());
    assertEquals(OrderStatus.CANCELLED, captor.getValue().getStatus());
    verify(stockDrainPort, never()).drain(any());
  }

  // ── error handling ─────────────────────────────────────────────────────────

  @Test
  void consume_malformedJson_discardsSilently() {
    // Should not throw — malformed JSON is logged and discarded.
    consumer.consume("not-valid-json");
    verify(orderMongoRepository, never()).findById(any());
  }

  @Test
  void consume_unknownOrderId_doesNothing() throws Exception {
    when(orderMongoRepository.findById("ord-x")).thenReturn(Optional.empty());

    consumer.consume(serialize("ord-x", "vhc-1", "completed", "2026-05-01T10:05:00Z"));

    verify(orderMongoRepository, never()).save(any());
    verify(stockDrainPort, never()).drain(any());
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private String serialize(String orderId, String vehicleId, String status, String timestamp)
      throws Exception {
    return objectMapper.writeValueAsString(
        new OrderStatusMessage("order.status", orderId, vehicleId, status, timestamp));
  }
}
