package com.usal.whbackend.api.metrics;

import java.time.Instant;
import java.util.List;

/**
 * The envelope every computed metric answers with (RFC_Metricas_Calculadas.md §5.2). {@code
 * paramsUsed} and {@code generatedAt} let a chart state what it was calculated with, and when.
 */
public record ComputedMetricResponse<T>(
    String metric, Object paramsUsed, Instant generatedAt, List<T> data) {

  public ComputedMetricResponse {
    data = List.copyOf(data);
  }
}
