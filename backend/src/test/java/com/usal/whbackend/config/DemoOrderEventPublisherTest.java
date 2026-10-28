package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.OrderMongoRepository;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.kafka.OrderCompletedMessage;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class DemoOrderEventPublisherTest {

  private static List<Order> seeded;
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeAll
  static void dataset() {
    seeded = new DemoDataset(raw -> "hash", DemoDataset.DEFAULT_QR_PREFIX).build().getOrders();
  }

  @SuppressWarnings("unchecked")
  private static KafkaTemplate<String, String> kafka() {
    return mock(KafkaTemplate.class);
  }

  @SuppressWarnings("unchecked")
  private static ArgumentCaptor<ProducerRecord<String, String>> captor() {
    return ArgumentCaptor.forClass(ProducerRecord.class);
  }

  private DemoOrderEventPublisher publisher(KafkaTemplate<String, String> kafka, long timeout) {
    return new DemoOrderEventPublisher(kafka, mapper, timeout);
  }

  private static Order order(String id, OrderStatus status, Instant completedAt) {
    Order o = new Order();
    o.setId(id);
    o.setStatus(status);
    o.setRequestedByUserId("u-" + id);
    o.setCompletedAt(completedAt);
    o.setItems(
        List.of(new OrderItem("p-1", "SKU-1", 2, null, true), new OrderItem("p-2", "SKU-2", 1)));
    return o;
  }

  @Test
  void completedEvents_areTheCompletedSeededOrdersOnly_withTheSameIdsUsersAndItems() {
    List<OrderCompletedMessage> events = DemoOrderEventPublisher.completedEvents(seeded);

    List<Order> completed =
        seeded.stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).toList();
    assertThat(completed).isNotEmpty().hasSizeLessThan(seeded.size());
    assertThat(events).hasSameSizeAs(completed);
    assertThat(events)
        .extracting(OrderCompletedMessage::orderId)
        .containsExactlyInAnyOrderElementsOf(completed.stream().map(Order::getId).toList());
    for (OrderCompletedMessage e : events) {
      Order o =
          completed.stream().filter(c -> c.getId().equals(e.orderId())).findFirst().orElseThrow();
      assertThat(e.userId()).isEqualTo(o.getRequestedByUserId());
      assertThat(e.completedAt()).isEqualTo(o.getCompletedAt().toString());
      assertThat(e.items())
          .extracting(
              OrderCompletedMessage.Item::sku,
              OrderCompletedMessage.Item::quantity,
              OrderCompletedMessage.Item::fromRecommendation)
          .containsExactlyElementsOf(
              o.getItems().stream()
                  .map(
                      i ->
                          org.assertj.core.groups.Tuple.tuple(
                              i.getSku(), i.getQuantity(), i.isFromRecommendation()))
                  .toList());
    }
    // The preload must carry the flag, or the conversion metric and the model would disagree.
    assertThat(events)
        .anyMatch(e -> e.items().stream().anyMatch(OrderCompletedMessage.Item::fromRecommendation));
  }

  @Test
  void completedEvents_areChronologicalByCompletedAt_withUndatedLast() {
    List<Order> orders =
        List.of(
            order("o3", OrderStatus.COMPLETED, Instant.parse("2025-03-01T00:00:00Z")),
            order("o0", OrderStatus.COMPLETED, null),
            order("o1", OrderStatus.COMPLETED, Instant.parse("2024-01-01T00:00:00Z")),
            order("x", OrderStatus.PENDING, null),
            order("o2", OrderStatus.COMPLETED, Instant.parse("2024-06-01T00:00:00Z")),
            order("c", OrderStatus.CANCELLED, Instant.parse("2024-02-01T00:00:00Z")));

    assertThat(DemoOrderEventPublisher.completedEvents(orders))
        .extracting(OrderCompletedMessage::orderId)
        .containsExactly("o1", "o2", "o3", "o0");
    List<Instant> seededTimes =
        DemoOrderEventPublisher.completedEvents(seeded).stream()
            .map(e -> Instant.parse(e.completedAt()))
            .toList();
    assertThat(seededTimes).isSorted();
  }

  @Test
  void publishAsync_sendsOneRecordPerCompletedOrderOnTheTopic_andCountsThem() {
    KafkaTemplate<String, String> kafka = kafka();
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    List<Order> orders =
        List.of(
            order("b", OrderStatus.COMPLETED, Instant.parse("2025-01-02T00:00:00Z")),
            order("a", OrderStatus.COMPLETED, Instant.parse("2025-01-01T00:00:00Z")),
            order("p", OrderStatus.PENDING, null));

    int sent = publisher(kafka, 30).publishAsync(orders).join();

    ArgumentCaptor<ProducerRecord<String, String>> sentRecords = captor();
    verify(kafka, org.mockito.Mockito.times(2)).send(sentRecords.capture());
    verify(kafka).flush();
    assertThat(sent).isEqualTo(2);
    assertThat(sentRecords.getAllValues())
        .extracting(ProducerRecord::topic)
        .containsOnly("order.completed");
    assertThat(sentRecords.getAllValues()).extracting(ProducerRecord::key).containsOnlyNulls();
    JsonNode first = mapper.readTree(sentRecords.getAllValues().get(0).value());
    assertThat(first.get("orderId").asString()).isEqualTo("a");
    assertThat(first.get("userId").asString()).isEqualTo("u-a");
    assertThat(first.get("items").get(0).get("fromRecommendation").asBoolean()).isTrue();
    assertThat(first.get("items").get(1).has("fromRecommendation")).isFalse();
  }

  @Test
  void payload_isByteIdenticalToWhatARealCompletionPublishes() {
    Order order = order("o-1", OrderStatus.COMPLETED, Instant.parse("2025-01-01T10:15:30Z"));
    order.setDestinationArea("AREA-A");
    KafkaTemplate<String, String> real = kafka();
    when(real.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    new OrderRepository(
            mock(OrderMongoRepository.class),
            real,
            mock(MongoTemplate.class),
            mapper,
            new NoOpTelemetryAdapter())
        .publishCompleted(order);
    ArgumentCaptor<ProducerRecord<String, String>> realRecord = captor();
    verify(real, org.mockito.Mockito.timeout(5000)).send(realRecord.capture());

    KafkaTemplate<String, String> seed = kafka();
    when(seed.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));
    publisher(seed, 30).publishAsync(List.of(order)).join();
    ArgumentCaptor<ProducerRecord<String, String>> seedRecord = captor();
    verify(seed).send(seedRecord.capture());

    assertThat(seedRecord.getValue().value()).isEqualTo(realRecord.getValue().value());
    assertThat(seedRecord.getValue().topic()).isEqualTo(realRecord.getValue().topic());
    assertThat(seedRecord.getValue().key()).isEqualTo(realRecord.getValue().key());
  }

  @Test
  void publishAsync_whenSendThrows_doesNotThrow_andReportsNothingSent() {
    KafkaTemplate<String, String> kafka = kafka();
    when(kafka.send(any(ProducerRecord.class)))
        .thenThrow(new org.apache.kafka.common.KafkaException("broker unreachable"));

    int sent =
        publisher(kafka, 30)
            .publishAsync(List.of(order("a", OrderStatus.COMPLETED, Instant.now())))
            .join();

    assertThat(sent).isZero();
  }

  @Test
  void publishAsync_whenAcksFail_doesNotThrow_andCountsOnlyTheAcknowledged() {
    KafkaTemplate<String, String> kafka = kafka();
    CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> failed =
        new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("not enough replicas"));
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null))
        .thenReturn(failed);
    List<Order> orders = new ArrayList<>();
    orders.add(order("a", OrderStatus.COMPLETED, Instant.parse("2025-01-01T00:00:00Z")));
    orders.add(order("b", OrderStatus.COMPLETED, Instant.parse("2025-01-02T00:00:00Z")));

    assertThat(publisher(kafka, 30).publishAsync(orders).join()).isEqualTo(1);
  }

  @Test
  void publishAsync_whenAcksNeverArrive_givesUpAtTheTimeout() {
    KafkaTemplate<String, String> kafka = kafka();
    when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

    int sent =
        publisher(kafka, 1)
            .publishAsync(List.of(order("a", OrderStatus.COMPLETED, Instant.now())))
            .join();

    assertThat(sent).isZero();
  }

  @Test
  void publishAsync_withNothingCompleted_sendsNothing() {
    KafkaTemplate<String, String> kafka = kafka();

    int sent =
        publisher(kafka, 30).publishAsync(List.of(order("p", OrderStatus.PENDING, null))).join();

    assertThat(sent).isZero();
    verifyNoInteractions(kafka);
  }
}
