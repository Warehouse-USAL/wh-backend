package com.usal.whbackend.api.metrics;

import com.usal.whbackend.api.Roles;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

  private final MetricsQueryService metricsQueryService;

  public MetricsController(MetricsQueryService metricsQueryService) {
    this.metricsQueryService = metricsQueryService;
  }

  @Operation(
      summary = "List queryable metrics",
      description =
          "Self-describing catalogue: each entry carries the dimensions and aggregations a valid"
              + " query may use, so a client can build a metric picker without further docs.")
  @ApiResponse(responseCode = "200", description = "Metrics visible to the caller")
  @GetMapping("/catalog")
  public ResponseEntity<Map<String, Object>> catalog(Authentication authentication) {
    var metrics =
        metricsQueryService.catalog(Roles.of(authentication)).stream()
            .map(MetricDescriptorResponse::from)
            .toList();
    return ResponseEntity.ok(Map.of("metrics", metrics));
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
}
