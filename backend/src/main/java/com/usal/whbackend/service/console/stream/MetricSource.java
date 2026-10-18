package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.StepDuration;
import java.time.Clock;
import java.time.Instant;
import tools.jackson.databind.json.JsonMapper;

/**
 * One metric query, re-run on the server because VictoriaMetrics is pull-only: this is the only
 * internal pull the stream needs, and it happens once per unique query however many streams
 * subscribe. The window is the console's: {@code to = floor(now / step) * step}, {@code from = to -
 * range + step}, epoch aligned, so it moves one step at a time and the payload is the {@code POST
 * /metrics/query} response for that window. Validation and role visibility are the metrics
 * service's.
 */
final class MetricSource extends ReplaceSource {

  private final SourceKey.Metric key;
  private final MetricsQueryService metrics;
  private final JsonMapper json;
  private final Clock clock;
  private final long stepSeconds;

  MetricSource(SourceKey.Metric key, MetricsQueryService metrics, JsonMapper json, Clock clock) {
    this.key = key;
    this.metrics = metrics;
    this.json = json;
    this.clock = clock;
    // The parser only builds keys whose step parses.
    this.stepSeconds = StepDuration.parse(key.step()).orElseThrow().getSeconds();
  }

  @Override
  protected Result fetch() {
    long now = clock.instant().getEpochSecond();
    long to = now - Math.floorMod(now, stepSeconds);
    long from = to - key.range().getSeconds() + stepSeconds;
    MetricsQueryRequest request =
        new MetricsQueryRequest(
            key.metric(),
            Instant.ofEpochSecond(from),
            Instant.ofEpochSecond(to),
            key.step(),
            key.filters(),
            key.groupBy(),
            key.agg());
    String payload = json.writeValueAsString(metrics.query(request, key.roles()));
    return new Result(payload, payload);
  }
}
