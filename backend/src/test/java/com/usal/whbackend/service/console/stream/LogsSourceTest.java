package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamFixtures.JSON;
import static com.usal.whbackend.service.console.stream.StreamFixtures.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.service.console.LogLine;
import com.usal.whbackend.service.console.LogsQuery;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.stream.StreamFixtures.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class LogsSourceTest {

  private static final Instant T0 = Instant.parse("2026-10-18T12:00:00Z");
  private static final Duration OVERLAP = Duration.ofSeconds(10);
  private static final SourceKey.Logs KEY =
      new SourceKey.Logs("backend", "error", null, "timeout", Duration.ofHours(1), 4);

  private final LogsQueryService logs = mock(LogsQueryService.class);
  private final MutableClock clock = new MutableClock(T0);
  private final LogsSource source = new LogsSource(KEY, logs, JSON, clock, OVERLAP);
  private final RecordingSink sink = new RecordingSink();

  private static LogLine line(int secondsAfterT0, String message) {
    return new LogLine(T0.plusSeconds(secondsAfterT0), "error", "backend", message, "req-1");
  }

  private static String json(List<LogLine> lines) {
    return JSON.writeValueAsString(new LogsSource.Page(lines, lines.size()));
  }

  private List<LogsQuery> queries() {
    ArgumentCaptor<LogsQuery> captor = ArgumentCaptor.forClass(LogsQuery.class);
    verify(logs, org.mockito.Mockito.atLeastOnce()).query(captor.capture());
    return captor.getAllValues();
  }

  @Test
  void snapshotIsTheRestResponseThenOnlyNewLinesOldestFirst() {
    LogLine l1 = line(-30, "a");
    LogLine l2 = line(-20, "b");
    LogLine l3 = line(-10, "c");
    LogLine l4 = line(1, "d");
    LogLine l5 = line(1, "e");
    when(logs.query(any()))
        .thenReturn(List.of(l3, l2, l1), List.of(l4, l3, l2), List.of(l5, l4), List.of(l5));
    source.join(subscriber("lg", sink));

    source.evaluate();
    clock.set(T0.plusSeconds(2));
    source.evaluate();
    clock.set(T0.plusSeconds(4));
    source.evaluate();
    source.evaluate();

    assertThat(sink.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"lg\",\"mode\":\"append\",\"data\":"
                + json(List.of(l3, l2, l1))
                + "}\n\n",
            "event: update\ndata: {\"id\":\"lg\",\"mode\":\"append\",\"data\":"
                + json(List.of(l4))
                + "}\n\n",
            "event: update\ndata: {\"id\":\"lg\",\"mode\":\"append\",\"data\":"
                + json(List.of(l5))
                + "}\n\n");
    List<LogsQuery> queries = queries();
    assertThat(queries.get(0))
        .isEqualTo(
            new LogsQuery("backend", "error", null, "timeout", T0.minusSeconds(3600), T0, 4));
    // Each later query starts the overlap before the newest line seen, to catch late arrivals.
    assertThat(queries.get(1).from()).isEqualTo(T0.minusSeconds(20));
    assertThat(queries.get(1).to()).isEqualTo(T0.plusSeconds(2));
    assertThat(queries.get(2).from()).isEqualTo(T0.minusSeconds(9));
  }

  @Test
  void newLinesOfOneEvaluationArriveOldestFirst() {
    LogLine old = line(-5, "old");
    LogLine n1 = line(1, "n1");
    LogLine n2 = line(2, "n2");
    when(logs.query(any())).thenReturn(List.of(old), List.of(n2, n1, old));
    source.join(subscriber("lg", sink));

    source.evaluate();
    source.evaluate();

    assertThat(sink.frames().get(1)).contains(json(List.of(n1, n2)));
  }

  @Test
  void aLateSubscriberGetsTheNewestLinesUpToTheLimit() {
    when(logs.query(any()))
        .thenReturn(
            List.of(line(-3, "c"), line(-4, "b"), line(-5, "a")),
            List.of(line(2, "e"), line(1, "d"), line(-3, "c")));
    source.join(subscriber("x", sink));
    source.evaluate();
    source.evaluate();
    RecordingSink late = new RecordingSink();

    source.join(subscriber("y", late));

    assertThat(late.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"y\",\"mode\":\"append\",\"data\":"
                + json(List.of(line(2, "e"), line(1, "d"), line(-3, "c"), line(-4, "b")))
                + "}\n\n");
  }

  @Test
  void withNoLinesTheCursorStartsAtTheFirstQuery() {
    when(logs.query(any())).thenReturn(List.of());
    source.join(subscriber("x", sink));

    source.evaluate();
    clock.set(T0.plusSeconds(2));
    source.evaluate();

    assertThat(queries().get(1).from()).isEqualTo(T0.minus(OVERLAP));
    assertThat(sink.frames()).hasSize(1);
  }

  @Test
  void theCursorNeverReachesBeforeTheRange() {
    SourceKey.Logs shortRange =
        new SourceKey.Logs(null, null, null, null, Duration.ofSeconds(5), 4);
    LogsSource src = new LogsSource(shortRange, logs, JSON, clock, OVERLAP);
    when(logs.query(any())).thenReturn(List.of());

    src.evaluate();
    src.evaluate();

    assertThat(queries().get(1).from()).isEqualTo(T0.minusSeconds(5));
  }

  @Test
  void linesWithoutATimeAreShownInTheSnapshotButNeverAppended() {
    LogLine timeless = new LogLine(null, "error", "backend", "?", null);
    when(logs.query(any())).thenReturn(List.of(timeless), List.of(timeless));
    source.join(subscriber("x", sink));

    source.evaluate();
    source.evaluate();

    assertThat(sink.frames()).hasSize(1);
    assertThat(sink.text()).contains("\"message\":\"?\"");
  }

  @Test
  void dedupeMemoryOnlyCoversTheOverlap() {
    when(logs.query(any()))
        .thenReturn(List.of(line(-30, "a"), line(-20, "b")), List.of(line(60, "c")));
    source.join(subscriber("x", sink));

    source.evaluate();
    clock.set(T0.plusSeconds(61));
    source.evaluate();

    assertThat(source.remembered()).isEqualTo(1);
  }

  @Test
  void aStoreOutageIsAnErrorAndTheNextSuccessIsTheSnapshot() {
    when(logs.query(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LOG_STORE_UNAVAILABLE"))
        .thenReturn(List.of(line(1, "a")));
    source.join(subscriber("x", sink));

    source.evaluate();
    source.evaluate();

    assertThat(sink.frames()).hasSize(2);
    assertThat(sink.frames().get(0)).contains("LOG_STORE_UNAVAILABLE");
    assertThat(sink.frames().get(1)).startsWith("event: snapshot");
  }
}
