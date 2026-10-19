package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.api.metrics.MetricsQueryResponse;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.StepDuration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/**
 * One metric query, re-run on the server because VictoriaMetrics is pull-only: this is the only
 * internal pull the stream needs, and it happens once per unique query however many streams
 * subscribe. The window is the console's: {@code to = floor(now / step) * step}, {@code from = to -
 * range + step}, epoch aligned, so it moves one step at a time and the payload is the {@code POST
 * /metrics/query} response for that window plus that {@code window} (ISO instants and the step), so
 * the client lays the series onto exactly the server's bucket grid instead of guessing it from its
 * own clock. Validation and role visibility are the metrics service's.
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
    MetricsQueryResponse response = metrics.query(request, key.roles());
    Window window =
        new Window(
            Instant.ofEpochSecond(from).toString(),
            Instant.ofEpochSecond(to).toString(),
            key.step());
    String payload =
        json.writeValueAsString(
            new Frame(
                response.metric(), response.unit(), response.step(), response.series(), window));
    return new Result(payload, payload);
  }

  /** The metrics response, field for field, plus the window it answers. */
  record Frame(
      String metric,
      String unit,
      String step,
      List<MetricsQueryResponse.Series> series,
      Window window) {}

  record Window(String from, String to, String step) {}
}
