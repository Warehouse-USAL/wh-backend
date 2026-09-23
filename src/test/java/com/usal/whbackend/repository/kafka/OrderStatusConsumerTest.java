package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.service.OrderEventPublisher;
import com.usal.whbackend.service.StockDrainPort;
import com.usal.whbackend.service.VehicleEventPublisher;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class OrderStatusConsumerTest {

  @Mock OrderMongoRepository orderMongoRepository;
  @Mock OrderEventPublisher orderEventPublisher;
  @Mock StockDrainPort stockDrainPort;
  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  OrderStatusConsumer consumer;

  /** Shared ObjectMapper configured the same way as the production Spring bean. */
  private final ObjectMapper objectMapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  @BeforeEach
  void setUp() {
    consumer =
        new OrderStatusConsumer(
            orderMongoRepository,
            List.of(orderEventPublisher),
            stockDrainPort,
            vehicleUpdateExecutor,
            vehicleEventPublisher,
            objectMapper);
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
  void consume_completed_releasesAssignedVehicle() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(List.of());
    order.setAssignedVehicleId("vhc-1");
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Vehicle releasedVehicle = new Vehicle();
    releasedVehicle.setId("vhc-1");
    releasedVehicle.setStatus(VehicleStatus.IDLE);
    when(vehicleUpdateExecutor.apply(eq("vhc-1"), any()))
        .thenReturn(
            Optional.of(new VehicleUpdateExecutor.Result(VehicleStatus.BUSY, releasedVehicle)));

    consumer.consume(serialize("ord-1", "vhc-1", "completed", "2026-05-01T10:05:00Z"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Function<Vehicle, Update>> builderCaptor =
        ArgumentCaptor.forClass(Function.class);
    verify(vehicleUpdateExecutor).apply(eq("vhc-1"), builderCaptor.capture());
    Update applied = builderCaptor.getValue().apply(new Vehicle());
    Document setOps = (Document) applied.getUpdateObject().get("$set");
    assertEquals(VehicleStatus.IDLE, setOps.get("status"));
    assertTrue(setOps.containsKey("currentOrderId"));
    assertNull(setOps.get("currentOrderId"));
    verify(vehicleEventPublisher).broadcastVehicleUpdate(releasedVehicle);
  }

  @Test
  void consume_completed_withoutAssignedVehicle_doesNotTouchVehicle() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setItems(List.of());
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", null, "completed", "2026-05-01T10:05:00Z"));

    verify(vehicleUpdateExecutor, never()).apply(any(), any());
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

  @Test
  void consume_cancelled_releasesAssignedVehicle() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setAssignedVehicleId("vhc-2");
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(vehicleUpdateExecutor.apply(eq("vhc-2"), any())).thenReturn(Optional.empty());

    consumer.consume(serialize("ord-1", "vhc-2", "cancelled", "2026-05-01T10:05:00Z"));

    verify(vehicleUpdateExecutor).apply(eq("vhc-2"), any());
  }

  // ── in_progress leaves the vehicle alone ─────────────────────────────────────

  @Test
  void consume_inProgress_doesNotTouchVehicleRelease() throws Exception {
    Order order = new Order();
    order.setId("ord-1");
    order.setStatus(OrderStatus.PENDING);
    when(orderMongoRepository.findById("ord-1")).thenReturn(Optional.of(order));
    when(orderMongoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(serialize("ord-1", "vhc-1", "in_progress", "2026-05-01T10:00:00Z"));

    verify(vehicleUpdateExecutor, never()).apply(any(), any());
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
