package com.usal.whbackend.service.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WsEventBufferTest {

  private static WsEvent event(int n) {
    return new WsEvent(Instant.EPOCH.plusSeconds(n), "push", "e" + n);
  }

  @Test
  void bufferKeepsOnlyTheLast200PerFeed() {
    WsEventBuffer buffer = new WsEventBuffer();
    for (int i = 0; i < 250; i++) {
      buffer.record("/ws/v1/orders", event(i));
    }

    var recent = buffer.recent("/ws/v1/orders");

    assertThat(recent).hasSize(200);
    assertThat(recent.getFirst().detail()).isEqualTo("e249");
    assertThat(recent.getLast().detail()).isEqualTo("e50");
  }

  @Test
  void feedsAreIndependent() {
    WsEventBuffer buffer = new WsEventBuffer();
    buffer.record("/ws/v1/orders", event(1));
    buffer.record("/ws/v1/vehicles", event(2));

    assertThat(buffer.recent("/ws/v1/orders")).extracting(WsEvent::detail).containsExactly("e1");
    assertThat(buffer.recent("/ws/v1/vehicles")).extracting(WsEvent::detail).containsExactly("e2");
    assertThat(buffer.recent("/ws/v1/stock/alerts")).isEmpty();
  }

  @Test
  void listenersSeeEachNewEventOfTheirFeedOnly() {
    WsEventBuffer buffer = new WsEventBuffer();
    java.util.List<WsEvent> seen = new java.util.ArrayList<>();
    java.util.function.Consumer<WsEvent> listener = seen::add;
    buffer.addListener("/ws/v1/orders", listener);

    buffer.record("/ws/v1/orders", event(1));
    buffer.record("/ws/v1/vehicles", event(2));
    buffer.removeListener("/ws/v1/orders", listener);
    buffer.record("/ws/v1/orders", event(3));

    assertThat(seen).extracting(WsEvent::detail).containsExactly("e1");
  }

  @Test
  void aFailingListenerNeitherBreaksRecordingNorOtherListeners() {
    WsEventBuffer buffer = new WsEventBuffer();
    java.util.List<WsEvent> seen = new java.util.ArrayList<>();
    buffer.addListener(
        "/ws/v1/orders",
        e -> {
          throw new IllegalStateException("boom");
        });
    buffer.addListener("/ws/v1/orders", seen::add);

    buffer.record("/ws/v1/orders", event(1));

    assertThat(seen).hasSize(1);
    assertThat(buffer.recent("/ws/v1/orders")).hasSize(1);
  }

  @Test
  void withRecentHandsOverTheCurrentContentsNewestFirst() {
    WsEventBuffer buffer = new WsEventBuffer();
    buffer.record("/ws/v1/orders", event(1));
    buffer.record("/ws/v1/orders", event(2));
    java.util.List<WsEvent> snapshot = new java.util.ArrayList<>();

    buffer.withRecent("/ws/v1/orders", snapshot::addAll);
    buffer.withRecent("/ws/v1/vehicles", events -> assertThat(events).isEmpty());

    assertThat(snapshot).extracting(WsEvent::detail).containsExactly("e2", "e1");
  }

  @Test
  void removingAnUnknownListenerIsHarmless() {
    WsEventBuffer buffer = new WsEventBuffer();
    buffer.removeListener("/ws/v1/orders", e -> {});
    buffer.record("/ws/v1/orders", event(1));
    assertThat(buffer.recent("/ws/v1/orders")).hasSize(1);
  }

  @Test
  void concurrentWritersKeepTheCapAndEveryListenerCallWhileReadersSnapshot() throws Exception {
    WsEventBuffer buffer = new WsEventBuffer();
    AtomicInteger heard = new AtomicInteger();
    buffer.addListener("/ws/v1/orders", e -> heard.incrementAndGet());
    int writers = 8;
    int perWriter = 500;
    ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    AtomicInteger oversized = new AtomicInteger();
    try {
      for (int w = 0; w < writers; w++) {
        futures.add(
            pool.submit(
                () -> {
                  go.await();
                  for (int i = 0; i < perWriter; i++) {
                    buffer.record("/ws/v1/orders", event(i));
                  }
                  return null;
                }));
      }
      futures.add(
          pool.submit(
              () -> {
                go.await();
                for (int i = 0; i < perWriter; i++) {
                  if (buffer.recent("/ws/v1/orders").size() > WsEventBuffer.CAPACITY) {
                    oversized.incrementAndGet();
                  }
                }
                return null;
              }));
      go.countDown();
    } finally {
      pool.shutdown();
    }
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    for (Future<?> future : futures) {
      future.get();
    }

    assertThat(buffer.recent("/ws/v1/orders")).hasSize(WsEventBuffer.CAPACITY);
    assertThat(heard).hasValue(writers * perWriter);
    assertThat(oversized).hasValue(0);
  }
}
