package com.usal.whbackend.service.metrics;

/** Instrument kind, which decides what aggregations make sense for a metric. */
public enum MetricType {
  GAUGE,
  COUNTER,
  HISTOGRAM
}
