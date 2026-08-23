package com.usal.whbackend.service.metrics;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Set;

/**
 * One queryable metric.
 *
 * @param name catalogue name, in OTel dotted form, e.g. {@code wh.vehicle.battery}
 * @param seriesName the name VictoriaMetrics actually stores, e.g. {@code wh_vehicle_battery}. Held
 *     explicitly rather than derived: the OTLP-to-Prometheus mapping depends on collector
 *     configuration (see config/otel/collector-config.yml), and guessing it yields queries that
 *     return empty results with no error. This value is verified against a running stack.
 * @param dimensions the only labels that may appear in {@code filters} or {@code group_by}
 */
public record MetricDescriptor(
    String name,
    String seriesName,
    String displayName,
    String unit,
    MetricType type,
    List<String> dimensions,
    List<Aggregation> permittedAggregations,
    Set<UserRole> requiredRoles) {

  public MetricDescriptor {
    dimensions = List.copyOf(dimensions);
    permittedAggregations = List.copyOf(permittedAggregations);
    requiredRoles = Set.copyOf(requiredRoles);
  }

  public boolean hasDimension(String dimension) {
    return dimensions.contains(dimension);
  }

  public boolean permits(Aggregation aggregation) {
    return permittedAggregations.contains(aggregation);
  }

  public boolean isVisibleTo(Set<UserRole> roles) {
    return roles.stream().anyMatch(requiredRoles::contains);
  }
}
