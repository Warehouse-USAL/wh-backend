package com.usal.whbackend.service.console.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class StreamSessionTest {

  /** Runs tasks only when told to, so frames pile up as they would behind a slow client. */
  static final class ManualExecutor implements Executor {
    final Queue<Runnable> tasks = new ArrayDeque<>();

    @Override
    public synchronized void execute(Runnable command) {
      tasks.add(command);
    }

    void runAll() {
      Runnable next;
      while ((next = poll()) != null) {
        next.run();
      }
    }

    private synchronized Runnable poll() {
      return tasks.poll();
    }
  }

  private final RecordingSink sink = new RecordingSink();
  private final List<StreamSession> closed = new ArrayList<>();

  private StreamSession session(int capacity, Executor executor) {
    return new StreamSession(sink, capacity, executor, closed::add);
  }

  @Test
  void framesHaveTheExactWireFormat() {
    StreamSession session = session(10, Runnable::run);

    session.offerData("cpu", true, true, "{\"x\":1}");
    session.offerData("logs", false, false, "{\"lines\":[]}");
    session.offerError("cpu", "UNKNOWN_METRIC", "La suscripción no es válida.");
    session.ping();
    session.end("token-expired");

    assertThat(sink.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"cpu\",\"mode\":\"replace\",\"data\":{\"x\":1}}\n\n",
            "event: update\ndata: {\"id\":\"logs\",\"mode\":\"append\",\"data\":{\"lines\":[]}}\n\n",
            "event: error\ndata: {\"id\":\"cpu\",\"code\":\"UNKNOWN_METRIC\","
                + "\"message\":\"La suscripción no es válida.\"}\n\n",
            ": ping\n\n",
            "event: end\ndata: {\"reason\":\"token-expired\"}\n\n");
    assertThat(sink.completed()).isTrue();
    assertThat(closed).containsExactly(session);
    assertThat(session.closeReason()).isEqualTo("token-expired");
    assertThat(session.isOpen()).isFalse();
    assertThat(session.isDone()).isTrue();
  }

  @Test
  void textIsJsonEscaped() {
    StreamSession session = session(10, Runnable::run);

    session.offerError("a", "C", "line\n\"quoted\"");

    assertThat(sink.text())
        .isEqualTo(
            "event: error\ndata: {\"id\":\"a\",\"code\":\"C\","
                + "\"message\":\"line\\n\\\"quoted\\\"\"}\n\n");
  }

  @Test
  void pendingReplaceFramesCoalesceToTheLatestPerSubscription() {
    ManualExecutor executor = new ManualExecutor();
    StreamSession session = session(10, executor);

    session.offerData("a", true, true, "1");
    session.offerData("a", false, true, "2");
    session.offerData("b", false, true, "x");
    session.offerData("a", false, true, "3");
    executor.runAll();

    // The snapshot keeps its event name but carries the newest payload.
    assertThat(sink.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"a\",\"mode\":\"replace\",\"data\":3}\n\n",
            "event: update\ndata: {\"id\":\"b\",\"mode\":\"replace\",\"data\":x}\n\n");
  }

  @Test
  void appendFramesAreNeverCoalesced() {
    ManualExecutor executor = new ManualExecutor();
    StreamSession session = session(10, executor);

    session.offerData("l", true, false, "1");
    session.offerData("l", false, false, "2");
    executor.runAll();

    assertThat(sink.frames()).hasSize(2);
  }

  @Test
  void anErrorIsNotOvertakenByALaterReplace() {
    ManualExecutor executor = new ManualExecutor();
    StreamSession session = session(10, executor);

    session.offerData("a", false, true, "1");
    session.offerError("a", "METRICS_UNAVAILABLE", "m");
    session.offerData("a", false, true, "2");
    executor.runAll();

    assertThat(sink.frames()).hasSize(3);
    assertThat(sink.frames().get(0)).contains("\"data\":1");
    assertThat(sink.frames().get(1)).startsWith("event: error");
    assertThat(sink.frames().get(2)).contains("\"data\":2");
  }

  @Test
  void overflowingTheQueueEndsTheStreamWithLimit() {
    ManualExecutor executor = new ManualExecutor();
    StreamSession session = session(3, executor);

    for (int i = 0; i < 4; i++) {
      session.offerData("l", false, false, String.valueOf(i));
    }
    session.offerData("l", false, false, "ignored");
    executor.runAll();

    // Pending frames are dropped: the client resubscribes and gets a fresh snapshot.
    assertThat(sink.frames()).containsExactly("event: end\ndata: {\"reason\":\"limit\"}\n\n");
    assertThat(session.closeReason()).isEqualTo("limit");
    assertThat(closed).containsExactly(session);
  }

  @Test
  void aFailedWriteClosesTheSession() {
    StreamSession session = session(10, Runnable::run);
    sink.fail();

    session.offerData("a", true, true, "1");
    session.offerData("a", false, true, "2");

    assertThat(session.isOpen()).isFalse();
    assertThat(session.closeReason()).isEqualTo("client-gone");
    assertThat(sink.completed()).isTrue();
    assertThat(closed).containsExactly(session);
    assertThat(session.isDone()).isTrue();
  }

  @Test
  void theContainerReportingTheClientGoneClosesTheSessionOnce() {
    StreamSession session = session(10, Runnable::run);

    sink.clientGone();
    sink.clientGone();
    session.close("send-timeout");
    session.end("server-shutdown");
    session.offerData("a", true, true, "1");

    assertThat(closed).containsExactly(session);
    assertThat(session.closeReason()).isEqualTo("client-gone");
    assertThat(sink.frames()).isEmpty();
  }

  @Test
  void pingIsSkippedWhileFramesAreWaiting() {
    ManualExecutor executor = new ManualExecutor();
    StreamSession session = session(10, executor);

    session.offerData("a", true, true, "1");
    session.ping();
    executor.runAll();
    session.ping();
    executor.runAll();

    assertThat(sink.frames()).hasSize(2);
    assertThat(sink.frames().get(1)).isEqualTo(": ping\n\n");
  }

  @Test
  void aSlowClientNeverBlocksTheProducerOrAnotherClient() {
    ExecutorService pool = Executors.newCachedThreadPool();
    try {
      RecordingSink slowSink = new RecordingSink();
      slowSink.block();
      StreamSession slow = new StreamSession(slowSink, 10, pool, s -> {});
      StreamSession fast = session(10, pool);

      slow.offerData("a", true, true, "1");
      await().atMost(Duration.ofSeconds(2)).until(() -> slow.sendingSinceNanos() != 0);
      long start = System.nanoTime();
      for (int i = 0; i < 5; i++) {
        slow.offerData("a", false, true, String.valueOf(i));
      }
      fast.offerData("a", true, true, "1");
      long producerMs = (System.nanoTime() - start) / 1_000_000;

      await().atMost(Duration.ofSeconds(2)).until(() -> sink.frames().size() == 1);
      assertThat(producerMs).isLessThan(500);
      assertThat(slowSink.frames()).isEmpty();

      slowSink.release();
      // The first frame was already being written; the five replaces behind it coalesced.
      await().atMost(Duration.ofSeconds(2)).until(() -> slowSink.frames().size() == 2);
      assertThat(slowSink.frames().get(1)).contains("\"data\":4");
      await().atMost(Duration.ofSeconds(2)).until(() -> slow.sendingSinceNanos() == 0);
    } finally {
      pool.shutdownNow();
    }
  }
}
