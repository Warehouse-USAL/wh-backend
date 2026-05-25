package com.usal.whbackend.api.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
  void getOrders_returns200WithEnvelope() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(sampleOrder), pageable, 1));

    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").isArray())
        .andExpect(jsonPath("$.pagination.total_elements").value(1))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.size").value(10))
        .andExpect(jsonPath("$.pagination.total_pages").value(1));
  }

  @Test
  @WithMockUser
  void getOrders_sizeExceedsMax_clampsTo50() throws Exception {
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(4);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/orders").param("size", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  @WithMockUser
  void getOrders_negativePage_clampsToZero() throws Exception {
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(4);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/orders").param("page", "-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(0));
  }

  @Test
  @WithMockUser
  void getOrders_zeroSize_clampsToOne() throws Exception {
    when(orderService.getOrders(any(), any(), any(), any(), any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(4);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/orders").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(1));
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

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void cancelOrder_withReasonBody_passesReasonToService() throws Exception {
    Order withReason = new Order();
    withReason.setId("test-id");
    withReason.setStatus(OrderStatus.CANCELLED);
    withReason.setCancelReason("Out of stock");
    withReason.setRequestedByUserId("user-1");
    withReason.setItems(java.util.List.of());
    withReason.setCreatedAt(java.time.Instant.now());
    when(orderService.cancelOrder(eq("test-id"), eq("Out of stock"))).thenReturn(withReason);

    mockMvc
        .perform(
            post("/orders/test-id/cancel")
                .contentType("application/json")
                .content("{\"reason\":\"Out of stock\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.cancel_reason").value("Out of stock"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void cancelOrder_withoutBody_reasonIsNull() throws Exception {
    when(orderService.cancelOrder(anyString(), isNull())).thenReturn(sampleOrder);

    mockMvc.perform(post("/orders/test-id/cancel")).andExpect(status().isOk());
  }
}
