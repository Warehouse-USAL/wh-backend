package com.usal.whbackend.api.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(OrderController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class OrderControllerSecurityTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderService orderService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void createOrder_withUnauthorizedRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"destination_area\":\"AREA-B\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(username = "user-id-1", roles = "ADMIN_SALES")
  void createOrder_withAdminSales_returns201() throws Exception {
    when(orderService.createOrder(any(), eq("user-id-1"))).thenReturn(new Order());
    mockMvc
        .perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"destination_area\":\"AREA-B\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void cancelOrder_withUnauthorizedRole_returns403() throws Exception {
    mockMvc.perform(post("/orders/order-1/cancel")).andExpect(status().isForbidden());
  }

  @Test
  void createOrder_withNoToken_returns401() throws Exception {
    mockMvc
        .perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"destination_area\":\"AREA-B\"}"))
        .andExpect(status().isUnauthorized());
  }

  // ── SUPERADMIN: should have full access to all order endpoints ─────────

  @Test
  @WithMockUser(username = "superadmin-id", roles = "SUPERADMIN")
  void createOrder_withSuperadmin_returns201() throws Exception {
    when(orderService.createOrder(any(), eq("superadmin-id"))).thenReturn(new Order());
    mockMvc
        .perform(
            post("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[],\"destination_area\":\"AREA-B\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void cancelOrder_withSuperadmin_returns200() throws Exception {
    when(orderService.cancelOrder(anyString(), any())).thenReturn(new Order());
    mockMvc.perform(post("/orders/order-1/cancel")).andExpect(status().isOk());
  }
}
