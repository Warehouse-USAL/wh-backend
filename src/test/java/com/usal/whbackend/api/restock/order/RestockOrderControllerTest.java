package com.usal.whbackend.api.restock.order;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.service.RestockOrderService;
import com.usal.whbackend.service.exception.RestockOrderNotFoundException;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(RestockOrderController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class RestockOrderControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean RestockOrderService restockOrderService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private RestockOrder order(String id) {
    RestockOrder o = new RestockOrder();
    o.setId(id);
    o.setProductId("product-1");
    o.setQuantityRequested(50);
    o.setSupplier("Distribuidora XYZ");
    o.setRequestedByUserId("user-1");
    o.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
    return o;
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void createRestockOrder_valid_returns201() throws Exception {
    when(restockOrderService.createRestockOrder(any(), eq("user-1"))).thenReturn(order("rso-1"));

    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"product-1\",\"quantity_requested\":50,\"supplier\":\"Distribuidora"
                        + " XYZ\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.restock_order.id").value("rso-1"))
        .andExpect(jsonPath("$.restock_order.quantity_requested").value(50));
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void createRestockOrder_unauthorizedRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"product-1\",\"quantity_requested\":50,\"supplier\":\"XYZ\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createRestockOrder_productNotFound_returns404() throws Exception {
    when(restockOrderService.createRestockOrder(any(), any()))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"ghost\",\"quantity_requested\":50,\"supplier\":\"XYZ\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PRODUCT_NOT_FOUND"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createRestockOrder_missingSupplier_returns400() throws Exception {
    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product_id\":\"product-1\",\"quantity_requested\":50}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrders_returns200WithPagination() throws Exception {
    when(restockOrderService.getRestockOrders(any(), any(), any(), any(), any()))
        .thenReturn(new PageImpl<>(java.util.List.of(order("rso-1")), PageRequest.of(0, 10), 1));

    mockMvc
        .perform(get("/restock/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_orders[0].id").value("rso-1"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrder_found_includesReceivedSoFar() throws Exception {
    when(restockOrderService.getRestockOrder("rso-1")).thenReturn(order("rso-1"));
    when(restockOrderService.computeReceivedSoFar("rso-1")).thenReturn(30);

    mockMvc
        .perform(get("/restock/orders/rso-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_order.quantity_requested").value(50))
        .andExpect(jsonPath("$.restock_order.quantity_received_so_far").value(30));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrder_notFound_returns404() throws Exception {
    when(restockOrderService.getRestockOrder("bad"))
        .thenThrow(new RestockOrderNotFoundException("bad"));

    mockMvc
        .perform(get("/restock/orders/bad"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_NOT_FOUND"));
  }
}
