package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.service.console.LogLine;
import com.usal.whbackend.service.console.LogsQuery;
import com.usal.whbackend.service.console.LogsQueryService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Follows one log search with a cursor. The snapshot is the {@code POST /console/logs/query}
 * response for the range (newest first). Each later evaluation asks for the lines from {@code
 * overlap} before the newest line seen up to now, and pushes only the ones not sent yet, oldest
 * first. The overlap catches lines the store indexes late (the OTLP exporter and the collector
 * batch them); lines are told apart by time, level, service, request id and message.
 *
 * <p>Chosen over VictoriaLogs' {@code /select/logsql/tail}: tailing holds one long-lived HTTP
 * response per search on the store, needs a streaming client and reconnect logic of its own, and
 * the existing repository already speaks the query endpoint. If more than {@code limit} lines
 * arrive within one interval, only the newest {@code limit} are pushed (as on the REST endpoint).
 */
final class LogsSource extends Source {

  /** Same fields, in the same order, as the REST response. */
  record Page(List<LogLine> lines, int total) {}

  private final SourceKey.Logs key;
  private final LogsQueryService logs;
  private final JsonMapper json;
  private final Clock clock;
  private final Duration overlap;

  // Touched by the evaluation only (one at a time).
  private final Map<LogLine, Instant> seen = new HashMap<>();
  private Instant cursor;

  // Guarded by lock: the newest lines, newest first, at most limit.
  private List<LogLine> window;

  LogsSource(
      SourceKey.Logs key, LogsQueryService logs, JsonMapper json, Clock clock, Duration overlap) {
    this.key = key;
    this.logs = logs;
    this.json = json;
    this.clock = clock;
    this.overlap = overlap;
  }

  @Override
  void evaluate() {
    Instant now = clock.instant();
    Instant start = now.minus(key.range());
    boolean first = cursor == null;
    Instant from = first ? start : max(cursor.minus(overlap), start);
    List<LogLine> lines;
    try {
      lines =
          logs.query(
              new LogsQuery(
                  key.service(), key.level(), key.requestId(), key.text(), from, now, key.limit()));
    } catch (RuntimeException e) {
      failed(e);
      return;
    }
    List<LogLine> fresh = new ArrayList<>();
    for (LogLine line : lines) {
      if (line.time() != null && seen.putIfAbsent(line, line.time()) == null) {
        fresh.add(line);
      }
    }
    fresh.sort(Comparator.comparing(LogLine::time));
    cursor = fresh.isEmpty() ? (first ? now : cursor) : max(cursor, last(fresh).time());
    Instant forget = cursor.minus(overlap);
    seen.values().removeIf(time -> time.isBefore(forget));

    synchronized (lock) {
      recovered();
      if (first) {
        window = List.copyOf(lines);
        String payload = page(window);
        for (Subscriber s : subscribers) {
          s.data(false, payload);
        }
        return;
      }
      if (fresh.isEmpty()) {
        return;
      }
      List<LogLine> merged = new ArrayList<>(fresh.reversed());
      merged.addAll(window);
      window = List.copyOf(merged.subList(0, Math.min(merged.size(), key.limit())));
      String payload = page(fresh);
      for (Subscriber s : subscribers) {
        s.data(false, payload);
      }
    }
  }

  @Override
  protected boolean onJoin(Subscriber subscriber) {
    if (window == null) {
      return false;
    }
    subscriber.data(false, page(window));
    return true;
  }

  /** Lines remembered for de-duplication. */
  int remembered() {
    return seen.size();
  }

  private String page(List<LogLine> lines) {
    return json.writeValueAsString(new Page(lines, lines.size()));
  }

  private static Instant max(Instant a, Instant b) {
    if (a == null) {
      return b;
    }
    return a.isAfter(b) ? a : b;
  }

  private static LogLine last(List<LogLine> lines) {
    return lines.get(lines.size() - 1);
  }
}
