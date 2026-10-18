package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamFixtures.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SourceRegistryTest {

  /** Counts what the registry does to it. */
  static final class FakeSource extends Source {
    final AtomicInteger evaluations = new AtomicInteger();
    final AtomicInteger starts = new AtomicInteger();
    final AtomicInteger stops = new AtomicInteger();
    volatile CountDownLatch gate;
    volatile boolean explode;

    @Override
    void evaluate() {
      evaluations.incrementAndGet();
      if (explode) {
        throw new IllegalStateException("boom");
      }
      CountDownLatch g = gate;
      if (g != null) {
        try {
          g.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      for (Subscriber s : subscribers) {
        s.data(true, String.valueOf(evaluations.get()));
      }
    }

    @Override
    void start() {
      starts.incrementAndGet();
    }

    @Override
    void stop() {
      stops.incrementAndGet();
    }
  }

  private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
  private final ExecutorService evaluators = Executors.newFixedThreadPool(2);
  private final List<FakeSource> created = new ArrayList<>();

  private SourceRegistry registry(Duration interval) {
    return new SourceRegistry(
        key -> {
          FakeSource source = new FakeSource();
          created.add(source);
          return source;
        },
        key -> key instanceof SourceKey.WsEvents ? null : interval,
        scheduler,
        evaluators);
  }

  @AfterEach
  void shutdown() {
    scheduler.shutdownNow();
    evaluators.shutdownNow();
  }

  @Test
  void equalKeysShareOneSourceAndItsEvaluations() {
    SourceRegistry registry = registry(Duration.ofMillis(20));
    RecordingSink a = new RecordingSink();
    RecordingSink b = new RecordingSink();

    registry.acquire(new SourceKey.Status(), subscriber("a", a));
    registry.acquire(new SourceKey.Status(), subscriber("b", b));

    assertThat(created).hasSize(1);
    assertThat(registry.size()).isEqualTo(1);
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> a.frames().size() >= 1 && b.frames().size() >= 1);
  }

  @Test
  void aSourceWithNoSubscribersStopsAtOnce() throws Exception {
    SourceRegistry registry = registry(Duration.ofMillis(10));
    Subscriber a = subscriber("a", new RecordingSink());
    Subscriber b = subscriber("b", new RecordingSink());
    registry.acquire(new SourceKey.Status(), a);
    registry.acquire(new SourceKey.Status(), b);
    FakeSource source = created.get(0);
    await().atMost(Duration.ofSeconds(2)).until(() -> source.evaluations.get() > 0);

    registry.release(new SourceKey.Status(), a);
    assertThat(registry.size()).isEqualTo(1);
    registry.release(new SourceKey.Status(), b);
    assertThat(registry.size()).isZero();
    assertThat(source.stops.get()).isEqualTo(1);
    Thread.sleep(50);
    int after = source.evaluations.get();
    Thread.sleep(100);

    assertThat(source.evaluations.get()).isEqualTo(after);
  }

  @Test
  void eventDrivenSourcesAreStartedNotScheduled() throws Exception {
    SourceRegistry registry = registry(Duration.ofMillis(10));
    Subscriber a = subscriber("a", new RecordingSink());

    registry.acquire(new SourceKey.WsEvents("/ws/v1/orders"), a);
    Thread.sleep(80);

    FakeSource source = created.get(0);
    assertThat(source.starts.get()).isEqualTo(1);
    assertThat(source.evaluations.get()).isZero();
    registry.release(new SourceKey.WsEvents("/ws/v1/orders"), a);
    assertThat(source.stops.get()).isEqualTo(1);
  }

  @Test
  void aSlowEvaluationIsNeverStackedUp() throws Exception {
    SourceRegistry registry = registry(Duration.ofMillis(5));
    CountDownLatch gate = new CountDownLatch(1);
    registry.acquire(new SourceKey.Status(), subscriber("a", new RecordingSink()));
    FakeSource source = created.get(0);
    source.gate = gate;
    await().atMost(Duration.ofSeconds(2)).until(() -> source.evaluations.get() >= 1);
    int during = source.evaluations.get();

    Thread.sleep(100);

    assertThat(source.evaluations.get()).isEqualTo(during);
    gate.countDown();
    source.gate = null;
    await().atMost(Duration.ofSeconds(2)).until(() -> source.evaluations.get() > during);
  }

  @Test
  void aFailingEvaluationKeepsTheScheduleAlive() {
    SourceRegistry registry = registry(Duration.ofMillis(5));
    registry.acquire(new SourceKey.Status(), subscriber("a", new RecordingSink()));
    FakeSource source = created.get(0);
    source.explode = true;

    await().atMost(Duration.ofSeconds(2)).until(() -> source.evaluations.get() >= 3);
  }

  @Test
  void releasingSomethingUnknownIsHarmless() {
    SourceRegistry registry = registry(Duration.ofMillis(5));
    registry.release(new SourceKey.Status(), subscriber("a", new RecordingSink()));
    assertThat(registry.size()).isZero();
  }

  @Test
  void aShutDownEvaluatorPoolDoesNotWedgeTheSource() {
    SourceRegistry registry = registry(Duration.ofMillis(5));
    evaluators.shutdownNow();
    registry.acquire(new SourceKey.Status(), subscriber("a", new RecordingSink()));
    FakeSource source = created.get(0);

    await().pollDelay(Duration.ofMillis(50)).atMost(Duration.ofSeconds(2)).until(() -> true);
    assertThat(source.inFlight.get()).isFalse();
  }
}
