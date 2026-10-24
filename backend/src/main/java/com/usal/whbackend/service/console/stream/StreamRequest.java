package com.usal.whbackend.service.console.stream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /console/stream}: the subscriptions one stream carries. Serialized
 * snake_case, so {@code groupBy} is {@code group_by} and {@code requestId} is {@code request_id}.
 *
 * <p>Collections are copied into unmodifiable wrappers rather than {@code List.copyOf}, which
 * rejects null entries: a null entry must reach the parser (or the metrics translator) so it can
 * answer 400 rather than 500.
 */
public record StreamRequest(List<Subscription> subscriptions) {

  public StreamRequest {
    subscriptions =
        subscriptions == null ? null : Collections.unmodifiableList(new ArrayList<>(subscriptions));
  }

  /**
   * One subscription. {@code id} and {@code kind} are always required; the other fields belong to
   * one kind each: {@code metric} uses metric, range, step, filters, group_by and agg (as {@code
   * POST /metrics/query}, with {@code range} instead of from/to); {@code logs} uses service, level,
   * request_id, text, range and limit (as {@code POST /console/logs/query}); {@code ws-events} uses
   * feed; {@code status} takes nothing.
   */
  public record Subscription(
      String id,
      String kind,
      String metric,
      String range,
      String step,
      Map<String, String> filters,
      List<String> groupBy,
      String agg,
      String service,
      String level,
      String requestId,
      String text,
      Integer limit,
      String feed) {

    public Subscription {
      filters = filters == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
      groupBy = groupBy == null ? null : Collections.unmodifiableList(new ArrayList<>(groupBy));
    }
  }
}
