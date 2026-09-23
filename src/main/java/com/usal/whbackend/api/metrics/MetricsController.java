package com.usal.whbackend.api.metrics;

import com.usal.whbackend.api.Roles;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/metrics")
@Tag(name = "Metrics", description = "Time-series metrics for dashboards")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM', 'ADMIN_WAREHOUSE', 'DASHBOARD')")
public class MetricsController {

  static final String RESTOCK_SUGGESTIONS = "restock_suggestions";

  private final MetricsQueryService metricsQueryService;
  private final RestockSuggestionService restockSuggestionService;

  public MetricsController(
      MetricsQueryService metricsQueryService, RestockSuggestionService restockSuggestionService) {
    this.metricsQueryService = metricsQueryService;
    this.restockSuggestionService = restockSuggestionService;
  }

  @Operation(
      summary = "List queryable metrics",
      description =
          "Self-describing catalogue: each entry carries the dimensions and aggregations a valid"
              + " query may use, so a client can build a metric picker without further docs."
              + " computed_metrics lists the metrics the backend calculates, each with its"
              + " endpoint and the params it requires.")
  @ApiResponse(responseCode = "200", description = "Metrics visible to the caller")
  @GetMapping("/catalog")
  public ResponseEntity<Map<String, Object>> catalog(Authentication authentication) {
    var metrics =
        metricsQueryService.catalog(Roles.of(authentication)).stream()
            .map(MetricDescriptorResponse::from)
            .toList();
    var computed =
        metricsQueryService.computedCatalog(Roles.of(authentication)).stream()
            .map(ComputedMetricDescriptorResponse::from)
            .toList();
    return ResponseEntity.ok(Map.of("metrics", metrics, "computed_metrics", computed));
  }

  @Operation(
      summary = "Query a metric over time",
      description = "Returns chart-ready series. Rejects unknown or over-broad queries.")
  @ApiResponse(responseCode = "200", description = "Time series")
  @ApiResponse(
      responseCode = "400",
      description = "UNKNOWN_METRIC, UNKNOWN_DIMENSION, UNSUPPORTED_AGGREGATION, QUERY_TOO_BROAD")
  @ApiResponse(responseCode = "503", description = "METRICS_UNAVAILABLE")
  @PostMapping("/query")
  public ResponseEntity<MetricsQueryResponse> query(
      @Valid @RequestBody MetricsQueryRequest request, Authentication authentication) {
    return ResponseEntity.ok(metricsQueryService.query(request, Roles.of(authentication)));
  }

  @Operation(
      summary = "Restock suggestions",
      description =
          "Weighted daily demand and, per active product, whether to restock and how much."
              + " Every param is required: each team sends its own business decisions."
              + " Inventory position = available (already net of reservations) + on order.")
  @ApiResponse(responseCode = "200", description = "One row per product, most urgent first")
  @ApiResponse(responseCode = "400", description = "INVALID_METRIC_PARAMS")
  @PostMapping("/restock-suggestions")
  public ResponseEntity<ComputedMetricResponse<RestockSuggestionRow>> restockSuggestions(
      @RequestBody RestockSuggestionsRequest request) {
    RestockParams params = request.toParams();
    var rows =
        restockSuggestionService
            .suggest(params, request.filters().productIds(), request.filters().category())
            .stream()
            .map(RestockSuggestionRow::from)
            .toList();
    return ResponseEntity.ok(
        new ComputedMetricResponse<>(RESTOCK_SUGGESTIONS, params, Instant.now(), rows));
  }
}
