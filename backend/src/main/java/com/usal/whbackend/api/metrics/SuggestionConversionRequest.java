package com.usal.whbackend.api.metrics;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import com.usal.whbackend.service.metrics.conversion.ConversionParams;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Body of {@code POST /metrics/suggestion-conversion}. Fields are boxed and unannotated on purpose,
 * like {@link RestockSuggestionsRequest}: a missing or invalid param is {@code
 * INVALID_METRIC_PARAMS} naming the param.
 */
public record SuggestionConversionRequest(ConversionRange params, ConversionFilters filters) {

  /**
   * @param from inclusive start of the range
   * @param to exclusive end of the range
   * @param granularity {@code day}, {@code week} (ISO, Monday) or {@code month}
   * @param timeZone optional IANA zone the buckets are cut in; UTC when absent
   */
  public record ConversionRange(Instant from, Instant to, String granularity, String timeZone) {}

  /**
   * @param productIds empty or absent means every product
   * @param category a catalogue category, case-insensitive
   */
  public record ConversionFilters(List<String> productIds, String category) {

    public ConversionFilters {
      productIds =
          productIds == null
              ? List.of()
              : List.copyOf(productIds.stream().filter(Objects::nonNull).toList());
    }
  }

  public SuggestionConversionRequest {
    filters = filters == null ? new ConversionFilters(null, null) : filters;
  }

  public ConversionParams toParams() {
    if (params == null) {
      throw new InvalidMetricParamsException("params es obligatorio");
    }
    return ConversionParams.validated(
        params.from(), params.to(), params.granularity(), params.timeZone());
  }
}
