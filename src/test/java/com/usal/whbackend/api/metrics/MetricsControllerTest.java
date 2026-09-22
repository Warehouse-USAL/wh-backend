package com.usal.whbackend.api.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.metrics.Aggregation;
import com.usal.whbackend.service.metrics.ComputedMetricDescriptor;
import com.usal.whbackend.service.metrics.MetricDescriptor;
import com.usal.whbackend.service.metrics.MetricType;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.restock.RestockResult;
import com.usal.whbackend.service.metrics.restock.RestockSuggestion;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(MetricsController.class)
@Import(GlobalExceptionHandler.class)
class MetricsControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean MetricsQueryService metricsQueryService;
  @MockitoBean JwtService jwtService;
  @MockitoBean RestockSuggestionService restockSuggestionService;

  private static final String QUERY_BODY =
      """
      {"metric":"wh.vehicle.battery","from":"2026-08-20T00:00:00Z","to":"2026-08-21T00:00:00Z",
       "step":"5m","filters":{},"group_by":["vehicle_id"],"agg":"avg"}
      """;

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void catalog_describesMetricsCompletelyEnoughToBuildAQuery() throws Exception {
    when(metricsQueryService.catalog(any()))
        .thenReturn(
            List.of(
                new MetricDescriptor(
                    "wh.vehicle.battery",
                    "wh_vehicle_battery",
                    "Vehicle battery",
                    "%",
                    MetricType.GAUGE,
                    List.of("vehicle_id"),
                    List.of(Aggregation.AVG, Aggregation.LAST),
                    Set.of(UserRole.DASHBOARD))));

    mockMvc
        .perform(get("/metrics/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics[0].name").value("wh.vehicle.battery"))
        .andExpect(jsonPath("$.metrics[0].unit").value("%"))
        .andExpect(jsonPath("$.metrics[0].type").value("gauge"))
        .andExpect(jsonPath("$.metrics[0].dimensions[0]").value("vehicle_id"))
        .andExpect(jsonPath("$.metrics[0].permitted_aggregations[0]").value("avg"))
        // The storage-level series name is an implementation detail and must not be exposed.
        .andExpect(jsonPath("$.metrics[0].series_name").doesNotExist());
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_returnsChartReadySeries() throws Exception {
    when(metricsQueryService.query(any(), any()))
        .thenReturn(
            new MetricsQueryResponse(
                "wh.vehicle.battery",
                "%",
                "5m",
                List.of(
                    new MetricsQueryResponse.Series(
                        Map.of("vehicle_id", "VHC-001"), List.of(List.of(1787184000L, 79.0))))));

    mockMvc
        .perform(post("/metrics/query").contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metric").value("wh.vehicle.battery"))
        .andExpect(jsonPath("$.series[0].labels.vehicle_id").value("VHC-001"))
        .andExpect(jsonPath("$.series[0].points[0][0]").value(1787184000L))
        .andExpect(jsonPath("$.series[0].points[0][1]").value(79.0));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_surfacesGuardRailRejectionsAsDocumentedCodes() throws Exception {
    when(metricsQueryService.query(any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD"));

    mockMvc
        .perform(post("/metrics/query").contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("QUERY_TOO_BROAD"));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_reportsAnUnavailableStoreAs503SoDashboardsCanDegrade() throws Exception {
    when(metricsQueryService.query(any(), any()))
        .thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE"));

    mockMvc
        .perform(post("/metrics/query").contentType(MediaType.APPLICATION_JSON).content(QUERY_BODY))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error.code").value("METRICS_UNAVAILABLE"));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void query_rejectsAMissingRequiredField() throws Exception {
    mockMvc
        .perform(
            post("/metrics/query")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"metric\":\"wh.vehicle.battery\",\"step\":\"5m\",\"agg\":\"avg\"}"))
        .andExpect(status().isBadRequest());
  }

  // ── Computed metrics ───────────────────────────────────────────────────────

  private static final String RESTOCK_BODY =
      """
      {"params":{"alpha":0.3,"recent_days":7,"long_days":60,"safety_days":2,
                 "lead_time_days":5,"coverage_days":7},
       "filters":{"product_ids":["p1"],"category":"tecnologia"}}
      """;

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void catalog_listsComputedMetricsWithTheParamsNeededToCallThem() throws Exception {
    when(metricsQueryService.catalog(any())).thenReturn(List.of());
    when(metricsQueryService.computedCatalog(any()))
        .thenReturn(
            List.of(
                new ComputedMetricDescriptor(
                    "restock_suggestions",
                    "/metrics/restock-suggestions",
                    "Sugerencia de reposición",
                    "desc",
                    List.of(new ComputedMetricDescriptor.Field("alpha", "number", 0.0, 1.0, "d")),
                    List.of(
                        new ComputedMetricDescriptor.Field(
                            "product_ids", "string[]", null, null, "d")),
                    Set.of(UserRole.DASHBOARD))));

    mockMvc
        .perform(get("/metrics/catalog"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metrics").isArray())
        .andExpect(jsonPath("$.computed_metrics[0].name").value("restock_suggestions"))
        .andExpect(jsonPath("$.computed_metrics[0].path").value("/metrics/restock-suggestions"))
        .andExpect(jsonPath("$.computed_metrics[0].params[0].name").value("alpha"))
        .andExpect(jsonPath("$.computed_metrics[0].params[0].max").value(1.0))
        .andExpect(jsonPath("$.computed_metrics[0].filters[0].min").doesNotExist())
        // Readers are an access rule, not part of the contract a caller builds requests from.
        .andExpect(jsonPath("$.computed_metrics[0].readers").doesNotExist());
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void restockSuggestions_returnsTheCommonEnvelopeWithRoundedValues() throws Exception {
    when(restockSuggestionService.suggest(any(), eq(List.of("p1")), eq("tecnologia")))
        .thenReturn(
            List.of(
                new RestockSuggestion(
                    "p1",
                    "SKU-1",
                    "Producto 1",
                    new RestockResult(
                        22, 25, 22.9000000001, 44, 158.5, 318.8, 140, 0, 140, true, 179))));

    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESTOCK_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metric").value("restock_suggestions"))
        .andExpect(jsonPath("$.params_used.alpha").value(0.3))
        .andExpect(jsonPath("$.params_used.lead_time_days").value(5.0))
        .andExpect(jsonPath("$.generated_at").isString())
        .andExpect(jsonPath("$.data[0].product_id").value("p1"))
        .andExpect(jsonPath("$.data[0].blended_demand").value(22.9))
        .andExpect(jsonPath("$.data[0].reorder_point").value(158.5))
        .andExpect(jsonPath("$.data[0].inventory_position").value(140))
        .andExpect(jsonPath("$.data[0].on_order_stock").value(0))
        .andExpect(jsonPath("$.data[0].should_restock").value(true))
        .andExpect(jsonPath("$.data[0].suggested_quantity").value(179));
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void restockSuggestions_withoutFiltersCoversEveryProduct() throws Exception {
    when(restockSuggestionService.suggest(any(), anyList(), isNull())).thenReturn(List.of());

    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"params":{"alpha":0.3,"recent_days":7,"long_days":60,"safety_days":2,
                               "lead_time_days":5,"coverage_days":7}}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void restockSuggestions_emptyBodyIsInvalidMetricParamsNot500() throws Exception {
    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_METRIC_PARAMS"))
        .andExpect(jsonPath("$.error.message").value("params es obligatorio"));
    verifyNoInteractions(restockSuggestionService);
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void restockSuggestions_outOfRangeParamNamesTheParam() throws Exception {
    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESTOCK_BODY.replace("\"alpha\":0.3", "\"alpha\":2")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_METRIC_PARAMS"))
        .andExpect(jsonPath("$.error.message").value("alpha debe estar entre 0 y 1"));
  }
}
