package com.usal.whbackend.api.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtAuthFilter;
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
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderService orderService;
  @MockitoBean JwtService jwtService;

  private Order sampleOrder;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    sampleOrder = new Order();
    sampleOrder.setStatus(OrderStatus.PENDING);
    sampleOrder.setRequestedByUserId("user-1");
    sampleOrder.setDestinationArea("AREA-A");
    sampleOrder.setItems(List.of());
    sampleOrder.setCreatedAt(Instant.now());
  }

  @Test
  @WithMockUser
  void getOrders_returns200() throws Exception {
    when(orderService.getOrders(any(), any(), any(), any())).thenReturn(List.of(sampleOrder));
    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").isArray());
  }

  @Test
  @WithMockUser
  void getOrder_returns200() throws Exception {
    when(orderService.getOrder(anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(get("/orders/test-id"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("pending"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void createOrder_returns201() throws Exception {
    when(orderService.createOrder(any(), anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(
            post("/orders")
                .contentType("application/json")
                .content("{\"items\":[],\"destination_area\":\"AREA-A\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.order").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void cancelOrder_returns200() throws Exception {
    when(orderService.cancelOrder(anyString(), any())).thenReturn(sampleOrder);
    mockMvc
        .perform(post("/orders/test-id/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("pending"));
  }
}
