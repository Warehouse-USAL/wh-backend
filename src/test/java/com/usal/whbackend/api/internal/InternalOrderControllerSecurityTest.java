package com.usal.whbackend.api.internal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

@WebMvcTest(InternalOrderController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class InternalOrderControllerSecurityTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderService orderService;
  @MockitoBean JwtService jwtService;

  private static final String BODY = "{\"vehicle_id\":\"vehicle-1\"}";

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void assignVehicle_withNoToken_returns401() throws Exception {
    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void assignVehicle_withAdminSales_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void assignVehicle_withProvider_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void assignVehicle_withAdminWarehouse_returns200() throws Exception {
    when(orderService.assignVehicle(anyString(), anyString())).thenReturn(new Order());
    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void assignVehicle_withSuperadmin_returns200() throws Exception {
    when(orderService.assignVehicle(anyString(), anyString())).thenReturn(new Order());
    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
  }
}
