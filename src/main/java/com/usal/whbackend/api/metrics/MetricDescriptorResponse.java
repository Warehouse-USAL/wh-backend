package com.usal.whbackend.api.metrics;

import com.usal.whbackend.service.metrics.Aggregation;
import com.usal.whbackend.service.metrics.MetricDescriptor;
import java.util.List;
import java.util.Locale;

/**
 * A catalogue entry. Complete enough that a consumer can build a valid query from it alone — that
 * is the point of the catalogue, and what lets Grupo 3 render a metric picker.
 *
 * <p>Note the absence of {@code seriesName}: the storage-level name is an implementation detail and
 * exposing it would invite consumers to bypass the query API.
 */
public record MetricDescriptorResponse(
    String name,
    String displayName,
    String unit,
    String type,
    List<String> dimensions,
    List<String> permittedAggregations) {

  public MetricDescriptorResponse {
    dimensions = List.copyOf(dimensions);
    permittedAggregations = List.copyOf(permittedAggregations);
  }

  public static MetricDescriptorResponse from(MetricDescriptor descriptor) {
    return new MetricDescriptorResponse(
        descriptor.name(),
        descriptor.displayName(),
        descriptor.unit(),
        descriptor.type().name().toLowerCase(Locale.ROOT),
        descriptor.dimensions(),
        descriptor.permittedAggregations().stream()
            .map(Aggregation::name)
            .map(a -> a.toLowerCase(Locale.ROOT))
            .toList());
  }
}
