package com.usal.whbackend.api.metrics;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A range query. Serialized snake_case, so {@code groupBy} is {@code group_by} on the wire.
 *
 * @param filters restricts which series are considered; keys must be declared dimensions
 * @param groupBy decides how the result is split into series; keys must be declared dimensions
 */
public record MetricsQueryRequest(
    @NotBlank String metric,
    @NotNull Instant from,
    @NotNull Instant to,
    @NotBlank String step,
    Map<String, String> filters,
    List<String> groupBy,
    @NotBlank String agg) {

  /**
   * Copies defensively and normalises absent collections to empty.
   *
   * <p>Deliberately not {@code Map.copyOf}/{@code List.copyOf}: those reject null entries, which
   * would turn a malformed body such as {@code {"filters":{"vehicle_id":null}}} into a 500 rather
   * than the documented 400 the translator produces.
   */
  public MetricsQueryRequest {
    filters =
        filters == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filters));
    groupBy =
        groupBy == null
            ? List.of()
            : Collections.unmodifiableList(new java.util.ArrayList<>(groupBy));
  }
}
