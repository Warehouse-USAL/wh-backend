package com.usal.whbackend.api.metrics;

import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Body of {@code POST /metrics/restock-suggestions}. Fields are boxed and unannotated on purpose: a
 * missing or out-of-range param is reported by {@link RestockParams#validated} as {@code
 * INVALID_METRIC_PARAMS} naming the param, the one error code of the computed-metric contract.
 */
public record RestockSuggestionsRequest(Params params, Filters filters) {

  public record Params(
      Double alpha,
      Integer recentDays,
      Integer longDays,
      Double safetyDays,
      Double leadTimeDays,
      Double coverageDays) {}

  /**
   * @param productIds empty or absent means every active product
   * @param category a catalogue category, case-insensitive
   */
  public record Filters(List<String> productIds, String category) {

    public Filters {
      productIds =
          productIds == null
              ? List.of()
              : Collections.unmodifiableList(
                  new ArrayList<>(productIds.stream().filter(Objects::nonNull).toList()));
    }
  }

  public RestockSuggestionsRequest {
    filters = filters == null ? new Filters(null, null) : filters;
  }

  public RestockParams toParams() {
    if (params == null) {
      throw new InvalidMetricParamsException("params es obligatorio");
    }
    return RestockParams.validated(
        params.alpha(),
        params.recentDays(),
        params.longDays(),
        params.safetyDays(),
        params.leadTimeDays(),
        params.coverageDays());
  }
}
