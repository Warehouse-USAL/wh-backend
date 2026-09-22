package com.usal.whbackend.api.metrics;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MetricsController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class MetricsControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean MetricsQueryService metricsQueryService;
  @MockitoBean JwtService jwtService;
  @MockitoBean RestockSuggestionService restockSuggestionService;

  @BeforeEach
  void setUp() {
    when(metricsQueryService.catalog(any())).thenReturn(List.of());
  }

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void dashboardRoleCanReadMetrics() throws Exception {
    mockMvc.perform(get("/metrics/catalog")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void adminWarehouseCanReadMetrics() throws Exception {
    mockMvc.perform(get("/metrics/catalog")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void operatorIsDenied() throws Exception {
    mockMvc.perform(get("/metrics/catalog")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void adminSalesIsDenied() throws Exception {
    mockMvc.perform(get("/metrics/catalog")).andExpect(status().isForbidden());
  }

  private static final String RESTOCK_BODY =
      "{\"params\":{\"alpha\":0.3,\"recent_days\":7,\"long_days\":60,\"safety_days\":2,"
          + "\"lead_time_days\":5,\"coverage_days\":7}}";

  @Test
  @WithMockUser(roles = "DASHBOARD")
  void dashboardRoleCanRequestRestockSuggestions() throws Exception {
    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESTOCK_BODY))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void operatorIsDeniedRestockSuggestions() throws Exception {
    mockMvc
        .perform(
            post("/metrics/restock-suggestions")
                .contentType(MediaType.APPLICATION_JSON)
                .content(RESTOCK_BODY))
        .andExpect(status().isForbidden());
  }
}
