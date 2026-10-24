package com.usal.whbackend.api.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.usal.whbackend.service.metrics.ComputedMetricDescriptor;
import java.util.List;

/**
 * A computed-metric catalogue entry: everything needed to build a valid request. Readers are left
 * out — they decide whether the entry is listed at all, not how to call it.
 */
public record ComputedMetricDescriptorResponse(
    String name,
    String path,
    String displayName,
    String description,
    List<FieldResponse> params,
    List<FieldResponse> filters) {

  /** Bounds are omitted rather than null when a field has none. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record FieldResponse(
      String name, String type, Double min, Double max, String description) {}

  public ComputedMetricDescriptorResponse {
    params = List.copyOf(params);
    filters = List.copyOf(filters);
  }

  public static ComputedMetricDescriptorResponse from(ComputedMetricDescriptor d) {
    return new ComputedMetricDescriptorResponse(
        d.name(),
        d.path(),
        d.displayName(),
        d.description(),
        d.params().stream().map(ComputedMetricDescriptorResponse::field).toList(),
        d.filters().stream().map(ComputedMetricDescriptorResponse::field).toList());
  }

  private static FieldResponse field(ComputedMetricDescriptor.Field f) {
    return new FieldResponse(f.name(), f.type(), f.min(), f.max(), f.description());
  }
}
