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
 *     return empty results with no error. This value is verified against a running stack. For a
 *     {@link MetricType#HISTOGRAM} it is the base name, e.g. {@code http_server_duration}; the
 *     translator appends {@code _count} for rate and increase.
 * @param dimensions the only labels that may appear in {@code filters} or {@code group_by}
 * @param bucketSeriesName the cumulative {@code le} series a {@link MetricType#HISTOGRAM}'s
 *     quantiles rate. Defaults to {@code <seriesName>_bucket}, the OTel convention; held explicitly
 *     only for scraped histograms stored otherwise, such as MinIO's unsuffixed {@code
 *     minio_s3_requests_ttfb_seconds_distribution}. Unused by other types.
 */
public record MetricDescriptor(
    String name,
    String seriesName,
    String displayName,
    String unit,
    MetricType type,
    List<String> dimensions,
    List<Aggregation> permittedAggregations,
    Set<UserRole> requiredRoles,
    String bucketSeriesName) {

  public MetricDescriptor {
    bucketSeriesName = bucketSeriesName == null ? seriesName + "_bucket" : bucketSeriesName;
    dimensions = List.copyOf(dimensions);
    permittedAggregations = List.copyOf(permittedAggregations);
    requiredRoles = Set.copyOf(requiredRoles);
    validateAggregationsMatchType(name, type, permittedAggregations);
  }

  /** A descriptor whose histogram buckets, if any, follow the OTel {@code _bucket} convention. */
  public MetricDescriptor(
      String name,
      String seriesName,
      String displayName,
      String unit,
      MetricType type,
      List<String> dimensions,
      List<Aggregation> permittedAggregations,
      Set<UserRole> requiredRoles) {
    this(
        name,
        seriesName,
        displayName,
        unit,
        type,
        dimensions,
        permittedAggregations,
        requiredRoles,
        null);
  }

  /**
   * Fails at startup rather than at request time if a descriptor offers an aggregation that makes
   * no sense for its instrument.
   *
   * <p>The mismatch is silent otherwise: {@code sum_over_time} of a counter returns a number, just
   * a meaningless one — the sum of its cumulative totals. A dashboard would plot it happily.
   */
  private static void validateAggregationsMatchType(
      String name, MetricType type, List<Aggregation> aggregations) {
    for (Aggregation aggregation : aggregations) {
      boolean valid =
          switch (type) {
            case COUNTER -> aggregation.isCounterDelta();
            case GAUGE -> !aggregation.isCounterDelta() && aggregation.quantile().isEmpty();
            // Rate and increase read the _count series; any *_over_time of a histogram is
            // as meaningless as on a counter, since its buckets and count are counters.
            case HISTOGRAM -> aggregation.isCounterDelta() || aggregation.quantile().isPresent();
          };
      if (!valid) {
        throw new IllegalArgumentException(
            "Metric %s is a %s and cannot permit %s".formatted(name, type, aggregation));
      }
    }
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
