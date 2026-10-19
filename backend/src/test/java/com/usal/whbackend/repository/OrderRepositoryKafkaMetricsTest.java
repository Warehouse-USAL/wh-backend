package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import com.usal.whbackend.telemetry.TelemetryPort;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

class OrderRepositoryKafkaMetricsTest {

  private final List<String> recorded = new CopyOnWriteArrayList<>();
  private final List<String> published = new CopyOnWriteArrayList<>();
  private final TelemetryPort telemetry =
      new NoOpTelemetryAdapter() {
        @Override
        public void recordKafkaMessage(String topic, String direction, boolean ok) {
          recorded.add(topic + "/" + direction + "/" + (ok ? "ok" : "error"));
        }

        @Override
        public void recordKafkaPublish(String topic, boolean ok, double durationMs) {
          if (durationMs >= 0) {
            published.add(topic + "/" + (ok ? "ok" : "error"));
          }
        }
      };

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final OrderMongoRepository mongo = mock(OrderMongoRepository.class);

  private final OrderRepository repository =
      new OrderRepository(mongo, kafka, mock(MongoTemplate.class), new ObjectMapper(), telemetry);

  private static Order order(String id) {
    Order order = new Order();
    order.setId(id);
    order.setItems(List.of());
    return order;
  }

  /** The publish runs on the repository's own executor thread. */
  private void awaitRecorded(int count) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (recorded.size() < count && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
  }

  @Test
  void kafkaPublishSuccessAndFailureAreCounted() throws InterruptedException {
    CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
    CompletableFuture<SendResult<String, String>> failed =
        CompletableFuture.failedFuture(new IllegalStateException("broker down"));
    when(kafka.send(any(ProducerRecord.class))).thenReturn(ok, failed);
    Order first = order("ord-1");
    Order second = order("ord-2");
    when(mongo.save(first)).thenReturn(first);
    when(mongo.save(second)).thenReturn(second);

    repository.save(first);
    repository.save(second);

    awaitRecorded(2);
    assertThat(recorded).containsExactly("orders/out/ok", "orders/out/error");
    assertThat(published).containsExactly("orders/ok", "orders/error");
  }

  @Test
  void aSendThatThrowsIsCountedAsAnErrorOnItsTopic() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("no producer"));

    repository.publishCompleted(order("ord-3"));

    awaitRecorded(1);
    assertThat(recorded).containsExactly("order.completed/out/error");
    // No acknowledgement to time: only the message counter sees a send that throws.
    assertThat(published).isEmpty();
  }
}
