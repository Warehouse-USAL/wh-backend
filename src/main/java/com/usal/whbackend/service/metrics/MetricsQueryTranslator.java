package com.usal.whbackend.service.metrics;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates a query against its descriptor, then renders MetricsQL.
 *
 * <p>Every rejection happens here, before anything reaches VictoriaMetrics, so a malformed or
 * over-broad request costs the shared box nothing. A metric, dimension or aggregation that is not
 * declared in {@link MetricRegistry} cannot be expressed.
 *
 * <p>Shape of the generated query:
 *
 * <pre>{@code
 * <operator> by (<group_by>) (<over_time_fn>(<series>{<filters>}[<step>]))
 * }</pre>
 *
 * The inner function collapses samples within a step bucket; the outer operator collapses series
 * down to exactly the requested {@code group_by} labels. The outer operator is always applied, so
 * incidental labels the exporter adds (such as {@code job}) never leak into the response.
 */
@Component
public class MetricsQueryTranslator {

  static final Duration MAX_RANGE = Duration.ofDays(31);
  static final Duration MIN_STEP = Duration.ofSeconds(10);
  static final long MAX_POINTS_PER_SERIES = 11000;

  public String translate(MetricsQueryRequest request, MetricDescriptor descriptor) {
    Aggregation aggregation = validatedAggregation(request, descriptor);
    Duration step = validatedStep(request);
    validateRange(request, step);
    validateDimensions(request, descriptor);

    String selector = renderSelector(descriptor, request.filters());
    String inner =
        "%s(%s[%s])".formatted(aggregation.overTimeFunction(), selector, request.step().trim());

    String grouping =
        request.groupBy().isEmpty()
            ? ""
            : " by (%s)".formatted(String.join(", ", request.groupBy()));

    return "%s%s(%s)".formatted(aggregation.operator(), grouping, inner);
  }

  private Aggregation validatedAggregation(
      MetricsQueryRequest request, MetricDescriptor descriptor) {
    Aggregation aggregation =
        Aggregation.parse(request.agg())
            .orElseThrow(
                () ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AGGREGATION"));
    if (!descriptor.permits(aggregation)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AGGREGATION");
    }
    return aggregation;
  }

  private Duration validatedStep(MetricsQueryRequest request) {
    Duration step =
        StepDuration.parse(request.step())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD"));
    if (step.compareTo(MIN_STEP) < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
    return step;
  }

  private void validateRange(MetricsQueryRequest request, Duration step) {
    if (!request.to().isAfter(request.from())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
    Duration range = Duration.between(request.from(), request.to());
    if (range.compareTo(MAX_RANGE) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
    if (range.getSeconds() / step.getSeconds() > MAX_POINTS_PER_SERIES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
  }

  private void validateDimensions(MetricsQueryRequest request, MetricDescriptor descriptor) {
    for (String key : request.filters().keySet()) {
      if (!descriptor.hasDimension(key)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_DIMENSION");
      }
    }
    for (String key : request.groupBy()) {
      if (!descriptor.hasDimension(key)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_DIMENSION");
      }
    }
  }

  private String renderSelector(MetricDescriptor descriptor, Map<String, String> filters) {
    if (filters.isEmpty()) {
      return descriptor.seriesName();
    }
    String matchers =
        filters.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> "%s=\"%s\"".formatted(e.getKey(), escape(e.getValue())))
            .collect(Collectors.joining(", "));
    return "%s{%s}".formatted(descriptor.seriesName(), matchers);
  }

  /**
   * Quotes a label value for interpolation. Control characters are rejected outright rather than
   * escaped — no legitimate label value contains them, and refusing is safer than encoding.
   */
  private String escape(String value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_DIMENSION");
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.isISOControl(value.charAt(i))) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_DIMENSION");
      }
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
