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
import com.usal.whbackend.domain.RestockOrderStatus;
import com.usal.whbackend.service.RestockOrderService;
import com.usal.whbackend.service.UserService;
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
  @MockitoBean UserService userService;

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
    o.setStatus(RestockOrderStatus.PENDING);
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
        .andExpect(jsonPath("$.restock_order.quantity_requested").value(50))
        .andExpect(jsonPath("$.restock_order.status").value("PENDING"));
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void createRestockOrder_withExpectedDeliveryDate_returns201() throws Exception {
    RestockOrder withDate = order("rso-1");
    withDate.setExpectedDeliveryDate(Instant.parse("2026-09-01T00:00:00Z"));
    when(restockOrderService.createRestockOrder(any(), eq("user-1"))).thenReturn(withDate);

    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"product-1\",\"quantity_requested\":50,"
                        + "\"supplier\":\"Distribuidora XYZ\","
                        + "\"expected_delivery_date\":\"2026-09-01T00:00:00Z\"}"))
        .andExpect(status().isCreated())
        .andExpect(
            jsonPath("$.restock_order.expected_delivery_date").value("2026-09-01T00:00:00Z"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createRestockOrder_expectedDateInPast_returns400() throws Exception {
    when(restockOrderService.createRestockOrder(any(), any()))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_EXPECTED_DELIVERY_DATE"));

    mockMvc
        .perform(
            post("/restock/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"product_id\":\"product-1\",\"quantity_requested\":50,"
                        + "\"supplier\":\"Distribuidora XYZ\","
                        + "\"expected_delivery_date\":\"2000-01-01T00:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_EXPECTED_DELIVERY_DATE"));
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
    when(restockOrderService.getRestockOrders(any(), any(), any(), any(), eq(false), any()))
        .thenReturn(new PageImpl<>(java.util.List.of(order("rso-1")), PageRequest.of(0, 10), 1));

    mockMvc
        .perform(get("/restock/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_orders[0].id").value("rso-1"))
        .andExpect(jsonPath("$.pagination.total_elements").value(1));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrders_withIncludeCancelled_forwardsParam() throws Exception {
    when(restockOrderService.getRestockOrders(any(), any(), any(), any(), eq(true), any()))
        .thenReturn(new PageImpl<>(java.util.List.of(order("rso-1")), PageRequest.of(0, 10), 1));

    mockMvc
        .perform(get("/restock/orders").param("includeCancelled", "true"))
        .andExpect(status().isOk());

    verify(restockOrderService).getRestockOrders(any(), any(), any(), any(), eq(true), any());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrder_found_includesReceivedSoFar() throws Exception {
    when(restockOrderService.getRestockOrder("rso-1", null)).thenReturn(order("rso-1"));
    when(restockOrderService.computeReceivedSoFar("rso-1")).thenReturn(30);

    mockMvc
        .perform(get("/restock/orders/rso-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_order.quantity_requested").value(50))
        .andExpect(jsonPath("$.restock_order.quantity_received_so_far").value(30))
        .andExpect(jsonPath("$.restock_order.status").value("PENDING"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getRestockOrder_notFound_returns404() throws Exception {
    when(restockOrderService.getRestockOrder("bad", null))
        .thenThrow(new RestockOrderNotFoundException("bad"));

    mockMvc
        .perform(get("/restock/orders/bad"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_NOT_FOUND"));
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void cancelRestockOrder_valid_returns200WithCancellation() throws Exception {
    RestockOrder cancelled = order("rso-1");
    cancelled.setStatus(RestockOrderStatus.CANCELLED);
    cancelled.setCancelledAt(Instant.parse("2026-10-01T12:00:00Z"));
    cancelled.setCancelledByUserId("user-1");
    cancelled.setCancellationReason("supplier closed");
    when(restockOrderService.cancelRestockOrder("rso-1", "supplier closed", "user-1"))
        .thenReturn(cancelled);
    when(restockOrderService.computeReceivedSoFar("rso-1")).thenReturn(0);

    mockMvc
        .perform(
            post("/restock/orders/rso-1/cancel")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"supplier closed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_order.status").value("CANCELLED"))
        .andExpect(jsonPath("$.restock_order.cancelled_by_user_id").value("user-1"))
        .andExpect(jsonPath("$.restock_order.cancellation_reason").value("supplier closed"));
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void cancelRestockOrder_noBody_cancelsWithoutReason() throws Exception {
    RestockOrder cancelled = order("rso-1");
    cancelled.setStatus(RestockOrderStatus.CANCELLED);
    cancelled.setCancelledAt(Instant.parse("2026-10-01T12:00:00Z"));
    cancelled.setCancelledByUserId("user-1");
    when(restockOrderService.cancelRestockOrder("rso-1", null, "user-1")).thenReturn(cancelled);
    when(restockOrderService.computeReceivedSoFar("rso-1")).thenReturn(0);

    mockMvc
        .perform(post("/restock/orders/rso-1/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_order.cancellation_reason").doesNotExist());
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void cancelRestockOrder_notFound_returns404() throws Exception {
    when(restockOrderService.cancelRestockOrder(eq("bad"), any(), eq("user-1")))
        .thenThrow(new RestockOrderNotFoundException("bad"));

    mockMvc
        .perform(post("/restock/orders/bad/cancel"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_NOT_FOUND"));
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void cancelRestockOrder_alreadyCancelled_returns409() throws Exception {
    when(restockOrderService.cancelRestockOrder(eq("rso-1"), any(), eq("user-1")))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "RESTOCK_ORDER_ALREADY_CANCELLED"));

    mockMvc
        .perform(post("/restock/orders/rso-1/cancel"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_ALREADY_CANCELLED"));
  }

  @Test
  @WithMockUser(username = "user-1", roles = "ADMIN_WAREHOUSE")
  void cancelRestockOrder_hasReceptions_returns409() throws Exception {
    when(restockOrderService.cancelRestockOrder(eq("rso-1"), any(), eq("user-1")))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.CONFLICT, "RESTOCK_ORDER_HAS_RECEPTIONS"));

    mockMvc
        .perform(post("/restock/orders/rso-1/cancel"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_HAS_RECEPTIONS"));
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void cancelRestockOrder_unauthorizedRole_returns403() throws Exception {
    mockMvc.perform(post("/restock/orders/rso-1/cancel")).andExpect(status().isForbidden());
  }

  // ── PROVIDER: sees only its own supplier's orders ───────────────────────

  @Test
  @WithMockUser(username = "provider-1", roles = "PROVIDER")
  void getRestockOrders_asProvider_forcesItsOwnSupplier() throws Exception {
    when(userService.supplierOf("provider-1")).thenReturn("Distribuidora XYZ");
    when(restockOrderService.getRestockOrders(
            any(), eq("Distribuidora XYZ"), any(), any(), anyBoolean(), any()))
        .thenReturn(new PageImpl<>(java.util.List.of(order("rso-1")), PageRequest.of(0, 10), 1));

    mockMvc
        .perform(get("/restock/orders").param("supplier", "Otro Proveedor"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.restock_orders[0].id").value("rso-1"));
  }

  @Test
  @WithMockUser(username = "provider-1", roles = "PROVIDER")
  void getRestockOrder_asProvider_isLimitedToItsSupplier() throws Exception {
    when(userService.supplierOf("provider-1")).thenReturn("Otro Proveedor");
    when(restockOrderService.getRestockOrder("rso-1", "Otro Proveedor"))
        .thenThrow(new RestockOrderNotFoundException("rso-1"));

    mockMvc
        .perform(get("/restock/orders/rso-1"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESTOCK_ORDER_NOT_FOUND"));
  }

  @Test
  @WithMockUser(username = "provider-1", roles = "PROVIDER")
  void getRestockOrders_asProviderWithoutSupplier_returns403() throws Exception {
    when(userService.supplierOf("provider-1"))
        .thenThrow(
            new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN, "PROVIDER_WITHOUT_SUPPLIER"));

    mockMvc
        .perform(get("/restock/orders"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("PROVIDER_WITHOUT_SUPPLIER"));
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void getRestockOrders_withUnauthorizedRole_returns403() throws Exception {
    mockMvc.perform(get("/restock/orders")).andExpect(status().isForbidden());
  }
}
