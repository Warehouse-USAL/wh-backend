package com.usal.whbackend.service.metrics;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Set;

/**
 * A metric the backend calculates from business data, as opposed to a raw series stored in
 * VictoriaMetrics ({@link MetricDescriptor}). Each one is served by its own {@code POST} endpoint
 * under the common contract of RFC_Metricas_Calculadas.md §5: params in, {@code {metric,
 * params_used, generated_at, data}} out.
 *
 * @param path the endpoint that computes it
 * @param params business params the caller must send, all required
 * @param filters optional restrictions on which entities are computed
 */
public record ComputedMetricDescriptor(
    String name,
    String path,
    String displayName,
    String description,
    List<Field> params,
    List<Field> filters,
    Set<UserRole> readers) {

  /**
   * One request field.
   *
   * @param type {@code number}, {@code integer}, {@code string} or {@code string[]}
   * @param min inclusive lower bound, null when unbounded or not numeric
   * @param max inclusive upper bound, null when unbounded or not numeric
   */
  public record Field(String name, String type, Double min, Double max, String description) {}

  public ComputedMetricDescriptor {
    params = List.copyOf(params);
    filters = List.copyOf(filters);
    readers = Set.copyOf(readers);
  }

  public boolean isVisibleTo(Set<UserRole> roles) {
    return roles.stream().anyMatch(readers::contains);
  }
}
