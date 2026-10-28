package com.usal.whbackend.config;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.repository.kafka.OrderCompletedMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Replays the completed orders of the demo seed on {@code order.completed}, so the suggestions
 * service (docs/SUGGESTIONS.md) builds its model from exactly the orders MongoDB holds. The seeder
 * writes through the plain Mongo repositories, so nothing else announces them.
 *
 * <p>Each payload is {@link OrderCompletedMessage#from(Order)} serialized with the application's
 * ObjectMapper and sent with the same {@link KafkaTemplate} as a real completion: byte-identical.
 * Events go out in {@code completedAt} order. It runs on its own daemon thread and never throws: a
 * missing broker must not fail or delay the boot, it only leaves the model empty (the log line says
 * how to recover).
 */
@Component
public class DemoOrderEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(DemoOrderEventPublisher.class);

  /** Dedicated thread: send() blocks while the broker is unreachable (see OrderRepository). */
  private static final Executor PUBLISHER =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "demo-order-event-publisher");
            t.setDaemon(true);
            return t;
          });

  private final KafkaTemplate<String, String> kafka;
  private final ObjectMapper objectMapper;
  private final long ackTimeoutSeconds;

  public DemoOrderEventPublisher(
      KafkaTemplate<String, String> kafka,
      ObjectMapper objectMapper,
      @Value("${SEED_DEMO_PUBLISH_TIMEOUT_SECONDS:120}") long ackTimeoutSeconds) {
    this.kafka = kafka;
    this.objectMapper = objectMapper;
    this.ackTimeoutSeconds = ackTimeoutSeconds;
  }

  /** The completed orders, oldest first (orders without a completion time last). */
  static List<OrderCompletedMessage> completedEvents(List<Order> orders) {
    return orders.stream()
        .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
        .sorted(
            Comparator.comparing(
                Order::getCompletedAt, Comparator.nullsLast(Comparator.<Instant>naturalOrder())))
        .map(OrderCompletedMessage::from)
        .toList();
  }

  /**
   * Publishes the completed orders in the background; the future yields how many were acknowledged
   * and never completes exceptionally.
   */
  public CompletableFuture<Integer> publishAsync(List<Order> orders) {
    return CompletableFuture.supplyAsync(() -> publish(orders), PUBLISHER)
        .exceptionally(
            ex -> {
              warn(ex);
              return 0;
            });
  }

  private int publish(List<Order> orders) {
    long started = System.nanoTime();
    List<OrderCompletedMessage> events = completedEvents(orders);
    if (events.isEmpty()) {
      return 0;
    }
    List<CompletableFuture<?>> acks = new ArrayList<>(events.size());
    try {
      for (OrderCompletedMessage event : events) {
        String json = objectMapper.writeValueAsString(event);
        acks.add(kafka.send(new ProducerRecord<>(OrderCompletedMessage.TOPIC, null, json)));
      }
      kafka.flush();
      CompletableFuture.allOf(acks.toArray(new CompletableFuture<?>[0]))
          .get(ackTimeoutSeconds, TimeUnit.SECONDS);
    } catch (JacksonException | ExecutionException | TimeoutException e) {
      warn(e);
      return (int) acks.stream().filter(a -> a.isDone() && !a.isCompletedExceptionally()).count();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      warn(e);
      return 0;
    }
    log.info(
        "Published {} order.completed events of the demo seed in {} ms.",
        events.size(),
        (System.nanoTime() - started) / 1_000_000);
    return events.size();
  }

  private static void warn(Throwable cause) {
    log.warn(
        "Could not publish the demo orders to order.completed ({}): the suggestions model starts"
            + " empty. Recover by starting Kafka and replaying: drop the database and the"
            + " suggestions Redis, then restart with SEED_DEMO=true.",
        String.valueOf(cause));
  }
}
