package com.usal.whbackend.api.stock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JacksonConfig;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.domain.StockAdjustmentType;
import com.usal.whbackend.service.StockAdjustmentResult;
import com.usal.whbackend.service.StockAdjustmentService;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(StockAdjustmentController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class, JacksonConfig.class})
class StockAdjustmentControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean StockAdjustmentService stockAdjustmentService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static StockAdjustment adjustment() {
    return new StockAdjustment(
        "adj-1",
        "pos-1",
        "prod-1",
        StockAdjustmentType.DAMAGE,
        "rotas",
        14,
        8,
        "user-1",
        Instant.parse("2026-01-01T00:00:00Z"),
        List.of("o1"));
  }

  private static Order cancelled() {
    Order o = new Order();
    o.setId("o1");
    o.setCancelReason("INSUFFICIENT_STOCK: product SKU-1 needs 4 units");
    return o;
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void post_recordsTheAdjustmentAndReturnsTheCancelledOrders() throws Exception {
    when(stockAdjustmentService.adjust("pos-1", 6, StockAdjustmentType.DAMAGE, "rotas"))
        .thenReturn(new StockAdjustmentResult(adjustment(), List.of(cancelled())));

    mockMvc
        .perform(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":6,\"type\":\"damage\",\"reason\":\"rotas\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.adjustment.id").value("adj-1"))
        .andExpect(jsonPath("$.adjustment.position_id").value("pos-1"))
        .andExpect(jsonPath("$.adjustment.product_id").value("prod-1"))
        .andExpect(jsonPath("$.adjustment.type").value("DAMAGE"))
        .andExpect(jsonPath("$.adjustment.quantity_before").value(14))
        .andExpect(jsonPath("$.adjustment.quantity_after").value(8))
        .andExpect(jsonPath("$.adjustment.adjusted_by").value("user-1"))
        .andExpect(jsonPath("$.adjustment.cancelled_order_ids[0]").value("o1"))
        .andExpect(jsonPath("$.cancelled_orders[0].id").value("o1"))
        .andExpect(
            jsonPath("$.cancelled_orders[0].cancel_reason")
                .value("INSUFFICIENT_STOCK: product SKU-1 needs 4 units"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void post_withoutAnOptionalTypeOrReason_passesNulls() throws Exception {
    when(stockAdjustmentService.adjust("pos-1", 1, null, null))
        .thenReturn(new StockAdjustmentResult(adjustment(), List.of()));

    mockMvc
        .perform(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.cancelled_orders").isEmpty());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void post_invalidQuantity_returns400WithoutCallingTheService() throws Exception {
    for (String body : List.of("{}", "{\"quantity\":0}", "{\"quantity\":-3}")) {
      mockMvc
          .perform(
              post("/warehouse/positions/pos-1/stock-adjustments")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isBadRequest());
    }
    verifyNoInteractions(stockAdjustmentService);
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void post_serviceRejection_isReportedWithItsCode() throws Exception {
    when(stockAdjustmentService.adjust(any(), eq(99), any(), any()))
        .thenThrow(
            new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_POSITION_STOCK"));

    mockMvc
        .perform(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":99}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("QUANTITY_EXCEEDS_POSITION_STOCK"));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void post_andPositionHistory_areForbiddenToOperators() throws Exception {
    mockMvc
        .perform(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get("/warehouse/positions/pos-1/stock-adjustments"))
        .andExpect(status().isForbidden());
  }

  @Test
  void post_anonymous_isRejected() throws Exception {
    mockMvc
        .perform(
            post("/warehouse/positions/pos-1/stock-adjustments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":1}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getPositionHistory_isPaginatedAndSnakeCase() throws Exception {
    when(stockAdjustmentService.getPositionHistory("pos-1", PageRequest.of(1, 5)))
        .thenReturn(new PageImpl<>(List.of(adjustment()), PageRequest.of(1, 5), 6));

    mockMvc
        .perform(get("/warehouse/positions/pos-1/stock-adjustments?page=1&size=5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stock_adjustments[0].id").value("adj-1"))
        .andExpect(jsonPath("$.stock_adjustments[0].created_at").exists())
        .andExpect(jsonPath("$.pagination.page").value(1))
        .andExpect(jsonPath("$.pagination.size").value(5))
        .andExpect(jsonPath("$.pagination.total_elements").value(6))
        .andExpect(jsonPath("$.pagination.total_pages").value(2));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getPositionHistory_clampsPageAndSize() throws Exception {
    when(stockAdjustmentService.getPositionHistory("pos-1", PageRequest.of(0, 50)))
        .thenReturn(new PageImpl<>(List.of()));
    when(stockAdjustmentService.getPositionHistory("pos-1", PageRequest.of(0, 1)))
        .thenReturn(new PageImpl<>(List.of()));

    mockMvc
        .perform(get("/warehouse/positions/pos-1/stock-adjustments?page=-4&size=500"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/warehouse/positions/pos-1/stock-adjustments?size=0"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getPositionHistory_unknownPosition_returns404() throws Exception {
    when(stockAdjustmentService.getPositionHistory(eq("ghost"), any()))
        .thenThrow(new PositionNotFoundException("ghost"));

    mockMvc
        .perform(get("/warehouse/positions/ghost/stock-adjustments"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("POSITION_NOT_FOUND"));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void getProductHistory_isReadableByAnyAuthenticatedRole() throws Exception {
    when(stockAdjustmentService.getProductHistory("prod-1", PageRequest.of(0, 10)))
        .thenReturn(new PageImpl<>(List.of(adjustment())));

    mockMvc
        .perform(get("/products/prod-1/stock-adjustments"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stock_adjustments[0].product_id").value("prod-1"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void getProductHistory_unknownProduct_returns404() throws Exception {
    when(stockAdjustmentService.getProductHistory(eq("ghost"), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    mockMvc
        .perform(get("/products/ghost/stock-adjustments"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  void getProductHistory_anonymous_isRejected() throws Exception {
    mockMvc.perform(get("/products/prod-1/stock-adjustments")).andExpect(status().isUnauthorized());
  }
}
