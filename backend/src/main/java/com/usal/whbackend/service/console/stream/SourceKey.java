package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.domain.UserRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Canonical parameters of a source. Equal keys share one source (one evaluation fanned out to every
 * subscriber), so each record holds already-normalised values.
 */
sealed interface SourceKey {

  /** Filters sorted by key, group_by sorted; roles decide metric visibility. */
  record Metric(
      String metric,
      Duration range,
      String step,
      Map<String, String> filters,
      List<String> groupBy,
      String agg,
      Set<UserRole> roles)
      implements SourceKey {

    /** Unmodifiable copies; not {@code Map.copyOf}, so a null filter value reaches validation. */
    public Metric {
      filters = Collections.unmodifiableMap(new TreeMap<>(filters));
      groupBy = Collections.unmodifiableList(new ArrayList<>(groupBy));
      roles = Set.copyOf(roles);
    }
  }

  /** Validated like {@code POST /console/logs/query}: blanks are null, limit is capped. */
  record Logs(
      String service, String level, String requestId, String text, Duration range, int limit)
      implements SourceKey {}

  /** {@code feed} is the buffer's feed string, such as {@code /ws/v1/orders}. */
  record WsEvents(String feed) implements SourceKey {}

  record Status() implements SourceKey {}
}
