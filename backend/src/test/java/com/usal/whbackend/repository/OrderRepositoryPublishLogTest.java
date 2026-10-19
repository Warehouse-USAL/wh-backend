package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.ObjectMapper;

/** The publish line carries the request id captured on the request thread. */
class OrderRepositoryPublishLogTest {

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);

  private final OrderRepository repository =
      new OrderRepository(
          mock(OrderMongoRepository.class),
          kafka,
          mock(MongoTemplate.class),
          new ObjectMapper(),
          new NoOpTelemetryAdapter());

  private final List<ILoggingEvent> lines = new CopyOnWriteArrayList<>();
  private final Logger logger = (Logger) LoggerFactory.getLogger(OrderRepository.class);
  private final AppenderBase<ILoggingEvent> appender =
      new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
          event.prepareForDeferredProcessing();
          lines.add(event);
        }
      };

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(appender);
    MDC.clear();
  }

  private ILoggingEvent publishCompleted(String requestId) throws InterruptedException {
    Order order = new Order();
    order.setId("ord-1");
    order.setItems(List.of());
    if (requestId != null) {
      MDC.put("request_id", requestId);
    }
    repository.publishCompleted(order);
    // The request ends (and the filter clears the MDC) before the executor gets to send.
    MDC.clear();
    long deadline = System.currentTimeMillis() + 2000;
    while (lines.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertThat(lines).hasSize(1);
    return lines.get(0);
  }

  @Test
  void aPublishedMessageIsLoggedWithTheRequestId() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

    ILoggingEvent line = publishCompleted("req-1");

    assertThat(line.getFormattedMessage()).matches("kafka publish order\\.completed ok \\d+ms");
    assertThat(line.getLevel()).isEqualTo(Level.INFO);
    assertThat(line.getMDCPropertyMap()).containsEntry("request_id", "req-1");
    assertThat(line.getThreadName()).isEqualTo("kafka-order-publisher");
  }

  @Test
  void aFailedPublishIsAnErrorWithTheRequestId() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

    ILoggingEvent line = publishCompleted("req-2");

    assertThat(line.getFormattedMessage())
        .matches("kafka publish order\\.completed failed \\d+ms: broker down");
    assertThat(line.getLevel()).isEqualTo(Level.ERROR);
    assertThat(line.getMDCPropertyMap()).containsEntry("request_id", "req-2");
  }

  @Test
  void aSendThatThrowsIsAnErrorWithTheRequestId() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class))).thenThrow(new IllegalStateException("no producer"));

    ILoggingEvent line = publishCompleted("req-3");

    assertThat(line.getFormattedMessage()).isEqualTo("kafka publish order.completed failed");
    assertThat(line.getLevel()).isEqualTo(Level.ERROR);
    assertThat(line.getMDCPropertyMap()).containsEntry("request_id", "req-3");
  }

  @Test
  void withoutARequestIdTheLineCarriesNone() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

    ILoggingEvent line = publishCompleted(null);

    assertThat(line.getMDCPropertyMap()).doesNotContainKey("request_id");
    assertThat(MDC.get("request_id")).isNull();
  }

  @Test
  void anUnsafeRequestIdIsNeverLogged() throws InterruptedException {
    when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(null));

    ILoggingEvent line = publishCompleted("evil\nvalue");

    assertThat(line.getMDCPropertyMap()).doesNotContainKey("request_id");
  }
}
