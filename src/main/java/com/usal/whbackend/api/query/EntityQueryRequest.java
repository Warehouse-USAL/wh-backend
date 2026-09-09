package com.usal.whbackend.api.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A whitelisted query over one entity. Serialized snake_case on the wire.
 *
 * <p>One request shape covers two modes. With no {@code group_by} and no {@code aggregates} it is a
 * filtered, sorted, paginated read and returns documents. With either present it becomes an
 * aggregation and returns one row per group. There is no second endpoint for the second mode: the
 * filters, the whitelist, the roles and the error contract are identical either way, so splitting
 * them would duplicate all four.
 *
 * @param unwind name of a declared array to flatten before grouping, e.g. {@code items}
 * @param timezone IANA zone used for date bucketing; Buenos Aires when absent, because a UTC day
 *     boundary would put three hours of every warehouse evening into the following day
 */
public record EntityQueryRequest(
    List<Filter> filters,
    List<SortSpec> sort,
    List<String> fields,
    Integer page,
    Integer size,
    String unwind,
    List<GroupSpec> groupBy,
    List<AggregateSpec> aggregates,
    String timezone) {

  public record Filter(String field, String op, Object value) {}

  public record SortSpec(String field, String dir) {}

  /**
   * One group key.
   *
   * @param bucket for instant fields only: {@code hour}, {@code day} or {@code month}
   * @param as output column name; defaults to the field with dots replaced by underscores
   */
  public record GroupSpec(String field, String bucket, String as) {}

  /**
   * One aggregate column.
   *
   * @param field required for every op except {@code count}
   */
  public record AggregateSpec(String op, String field, String as) {}

  /**
   * Copies defensively and normalises absent collections to empty.
   *
   * <p>Not {@code List.copyOf}: a body containing a null element would otherwise become a 500
   * instead of a validation failure.
   */
  public EntityQueryRequest {
    filters = filters == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(filters));
    sort = sort == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sort));
    fields = fields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fields));
    groupBy = groupBy == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(groupBy));
    aggregates =
        aggregates == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(aggregates));
  }

  /** Whether this request should be answered by an aggregation pipeline rather than a find. */
  public boolean isAggregation() {
    return !groupBy.isEmpty() || !aggregates.isEmpty();
  }
}
