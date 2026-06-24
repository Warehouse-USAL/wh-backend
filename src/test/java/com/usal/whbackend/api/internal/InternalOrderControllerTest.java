package com.usal.whbackend.api.internal;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.service.OrderService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(InternalOrderController.class)
@Import(SecurityConfig.class)
class InternalOrderControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderService orderService;
  @MockitoBean JwtService jwtService;

  private Order inProgressOrder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    inProgressOrder = new Order();
    inProgressOrder.setId("order-1");
    inProgressOrder.setStatus(OrderStatus.IN_PROGRESS);
    inProgressOrder.setAssignedVehicleId("vehicle-1");
    inProgressOrder.setRequestedByUserId("user-1");
    inProgressOrder.setItems(List.of());
    inProgressOrder.setCreatedAt(Instant.now());
    inProgressOrder.setStartedAt(Instant.now());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void assignVehicle_returns200WithUpdatedOrder() throws Exception {
    when(orderService.assignVehicle(eq("order-1"), eq("vehicle-1"))).thenReturn(inProgressOrder);

    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicle_id\":\"vehicle-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("in_progress"))
        .andExpect(jsonPath("$.order.assigned_vehicle_id").value("vehicle-1"));
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void assignVehicle_withSuperadmin_returns200() throws Exception {
    when(orderService.assignVehicle(eq("order-1"), eq("vehicle-1"))).thenReturn(inProgressOrder);

    mockMvc
        .perform(
            patch("/internal/orders/order-1/assign-vehicle")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"vehicle_id\":\"vehicle-1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void changeStatus_returns200WithUpdatedOrder() throws Exception {
    Order completed = new Order();
    completed.setId("order-1");
    completed.setStatus(OrderStatus.COMPLETED);
    completed.setRequestedByUserId("user-1");
    completed.setItems(List.of());
    completed.setCreatedAt(Instant.now());
    when(orderService.changeStatus(eq("order-1"), eq("completed"))).thenReturn(completed);

    mockMvc
        .perform(
            patch("/internal/orders/order-1/status")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"completed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("completed"));
  }
}
