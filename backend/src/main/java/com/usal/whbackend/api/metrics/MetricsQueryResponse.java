package com.usal.whbackend.api.metrics;

import java.util.List;
import java.util.Map;

/**
 * Chart-ready result: a consumer maps {@code series[].points} straight onto an axis.
 *
 * @param series each point is a two-element {@code [epochSeconds, value]} pair
 */
public record MetricsQueryResponse(String metric, String unit, String step, List<Series> series) {

  public MetricsQueryResponse {
    series = List.copyOf(series);
  }

  public record Series(Map<String, String> labels, List<List<Number>> points) {
    public Series {
      labels = Map.copyOf(labels);
      points = List.copyOf(points);
    }
  }
}
