package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.api.websocket.WsActivity;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.console.LogsQuery;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.metrics.StepDuration;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates a stream request before the stream starts, so a client bug is a plain 400 rather than a
 * stream of errors. Metric checks that depend on the catalogue or the caller (unknown or hidden
 * metric, undeclared dimension, aggregation) are left to the metrics service, per subscription.
 */
final class StreamRequestParser {

  static final String INVALID = "INVALID_SUBSCRIPTION";

  /** The console's range picker, also the only windows a stream can follow. */
  static final Map<String, Duration> RANGES =
      Map.of(
          "5m", Duration.ofMinutes(5),
          "15m", Duration.ofMinutes(15),
          "1h", Duration.ofHours(1),
          "6h", Duration.ofHours(6),
          "24h", Duration.ofHours(24));

  private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

  private final LogsQueryService logs;
  private final int maxSubscriptions;

  StreamRequestParser(LogsQueryService logs, int maxSubscriptions) {
    this.logs = logs;
    this.maxSubscriptions = maxSubscriptions;
  }

  List<ParsedSubscription> parse(StreamRequest request, Set<UserRole> roles) {
    if (request == null
        || request.subscriptions() == null
        || request.subscriptions().isEmpty()
        || request.subscriptions().size() > maxSubscriptions) {
      throw invalid();
    }
    Set<String> ids = new HashSet<>();
    List<ParsedSubscription> parsed = new ArrayList<>();
    for (StreamRequest.Subscription sub : request.subscriptions()) {
      if (sub == null
          || sub.id() == null
          || !ID.matcher(sub.id()).matches()
          || !ids.add(sub.id())) {
        throw invalid();
      }
      parsed.add(new ParsedSubscription(sub.id(), key(sub, roles)));
    }
    return parsed;
  }

  private SourceKey key(StreamRequest.Subscription sub, Set<UserRole> roles) {
    String kind = sub.kind() == null ? "" : sub.kind();
    return switch (kind) {
      case "metric" -> metric(sub, roles);
      case "logs" -> logs(sub);
      case "ws-events" -> wsEvents(sub);
      case "status" -> new SourceKey.Status();
      default -> throw invalid();
    };
  }

  private static SourceKey metric(StreamRequest.Subscription sub, Set<UserRole> roles) {
    Duration range = range(sub.range());
    String step = sub.step() == null ? "" : sub.step().trim();
    Duration stepDuration = StepDuration.parse(step).orElseThrow(StreamRequestParser::invalid);
    if (isBlank(sub.metric()) || isBlank(sub.agg()) || stepDuration.compareTo(range) >= 0) {
      throw invalid();
    }
    Map<String, String> filters = new TreeMap<>();
    if (sub.filters() != null) {
      filters.putAll(sub.filters());
    }
    List<String> groupBy =
        sub.groupBy() == null ? new ArrayList<>() : new ArrayList<>(sub.groupBy());
    groupBy.sort(null);
    return new SourceKey.Metric(
        sub.metric().trim(), range, step, filters, groupBy, sub.agg().trim(), Set.copyOf(roles));
  }

  private SourceKey logs(StreamRequest.Subscription sub) {
    Duration range = range(sub.range());
    LogsQuery valid =
        logs.validate(
            new LogsQuery(
                sub.service(), sub.level(), sub.requestId(), sub.text(), null, null, sub.limit()));
    return new SourceKey.Logs(
        valid.service(), valid.level(), valid.requestId(), valid.text(), range, valid.limit());
  }

  private static SourceKey wsEvents(StreamRequest.Subscription sub) {
    String feed = sub.feed() == null ? null : WsActivity.FEEDS_BY_SLUG.get(sub.feed());
    if (feed == null) {
      throw invalid();
    }
    return new SourceKey.WsEvents(feed);
  }

  private static Duration range(String raw) {
    Duration range = raw == null ? null : RANGES.get(raw);
    if (range == null) {
      throw invalid();
    }
    return range;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, INVALID);
  }
}
