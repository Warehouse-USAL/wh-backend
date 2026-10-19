package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

class OrderRepositoryRequestIdTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final OrderMongoRepository mongo = mock(OrderMongoRepository.class);

  private final OrderRepository repository =
      new OrderRepository(
          mongo, kafka, mock(MongoTemplate.class), new ObjectMapper(), new NoOpTelemetryAdapter());

  private final List<ProducerRecord<String, String>> sent = new CopyOnWriteArrayList<>();
  private final Map<String, String> sendingThread = new ConcurrentHashMap<>();

  @SuppressWarnings("unchecked")
  OrderRepositoryRequestIdTest() {
    when(kafka.send(any(ProducerRecord.class)))
        .thenAnswer(
            inv -> {
              ProducerRecord<String, String> record = inv.getArgument(0);
              sendingThread.put(record.topic(), Thread.currentThread().getName());
              sent.add(record);
              return CompletableFuture.completedFuture(null);
            });
  }

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private static Order order(String id) {
    Order order = new Order();
    order.setId(id);
    order.setItems(List.of());
    return order;
  }

  private void publishBoth() throws InterruptedException {
    Order order = order("ord-1");
    when(mongo.save(order)).thenReturn(order);
    repository.save(order);
    repository.publishCompleted(order);
    // The request ends (and the filter clears the MDC) before the executor gets to send.
    MDC.clear();
    long deadline = System.currentTimeMillis() + 2000;
    while (sent.size() < 2 && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertThat(sent).hasSize(2);
  }

  private static String requestIdHeader(ProducerRecord<String, String> record) {
    Header header = record.headers().lastHeader("x-request-id");
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  @Test
  void kafkaMessageCarriesTheMdcRequestId() throws InterruptedException {
    MDC.put("request_id", "req-123_abc");

    publishBoth();

    assertThat(sent).allSatisfy(r -> assertThat(requestIdHeader(r)).isEqualTo("req-123_abc"));
    assertThat(sent)
        .extracting(ProducerRecord::topic)
        .containsExactlyInAnyOrder("orders", "order.completed");
    // Captured on the request thread, attached on the publisher thread.
    assertThat(sendingThread.values()).containsOnly("kafka-order-publisher");
    ProducerRecord<String, String> orders =
        sent.stream().filter(r -> r.topic().equals("orders")).findFirst().orElseThrow();
    assertThat(orders.key()).isEqualTo("ord-1");
    assertThat(orders.partition()).isNull();
    ProducerRecord<String, String> completed =
        sent.stream().filter(r -> r.topic().equals("order.completed")).findFirst().orElseThrow();
    assertThat(completed.key()).isNull();
    assertThat(completed.partition()).isNull();
  }

  @Test
  void noMdcMeansNoHeader() throws InterruptedException {
    publishBoth();

    assertThat(sent).allSatisfy(r -> assertThat(r.headers().toArray()).isEmpty());
  }

  @Test
  void anUnsafeMdcValueNeverReachesKafka() throws InterruptedException {
    MDC.put("request_id", "evil\nvalue");

    publishBoth();

    assertThat(sent).allSatisfy(r -> assertThat(r.headers().toArray()).isEmpty());
  }
}
