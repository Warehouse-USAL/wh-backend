package com.usal.whbackend.api.order;

import static org.assertj.core.api.Assertions.assertThat;
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
  @WithMockUser(roles = "ADMIN_SALES")
  void createOrder_readsTheOptionalFromRecommendationFlagOfEachItem() throws Exception {
    when(orderService.createOrder(any(), anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(
            post("/orders")
                .contentType("application/json")
                .content(
                    "{\"items\":[{\"product_id\":\"p1\",\"quantity\":1,"
                        + "\"from_recommendation\":true},"
                        + "{\"product_id\":\"p2\",\"quantity\":2},"
                        + "{\"product_id\":\"p3\",\"quantity\":1,\"from_recommendation\":null}],"
                        + "\"destination_area\":\"AREA-A\"}"))
        .andExpect(status().isCreated());

    org.mockito.ArgumentCaptor<CreateOrderRequest> captor =
        org.mockito.ArgumentCaptor.forClass(CreateOrderRequest.class);
    org.mockito.Mockito.verify(orderService).createOrder(captor.capture(), anyString());
    assertThat(captor.getValue().items())
        .extracting(CreateOrderRequest.OrderItemRequest::recommended)
        .containsExactly(true, false, false);
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void createOrder_withIdempotencyKey_passesHeaderToService() throws Exception {
    when(orderService.createOrder(any(), anyString(), eq("key-abc-123"))).thenReturn(sampleOrder);
    mockMvc
        .perform(
            post("/orders")
                .header("Idempotency-Key", "key-abc-123")
                .contentType("application/json")
                .content("{\"items\":[],\"destination_area\":\"AREA-A\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.order").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void createOrder_withBlankIdempotencyKey_callsStandardCreateOrder() throws Exception {
    when(orderService.createOrder(any(), anyString())).thenReturn(sampleOrder);
    mockMvc
        .perform(
            post("/orders")
                .header("Idempotency-Key", "   ")
                .contentType("application/json")
                .content("{\"items\":[],\"destination_area\":\"AREA-A\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.order").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void cancelOrder_returns200() throws Exception {
    when(orderService.cancelOrder(anyString(), any(), anyString(), eq(true)))
        .thenReturn(sampleOrder);
    mockMvc
        .perform(post("/orders/test-id/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("pending"));
  }

  @Test
  @WithMockUser(username = "operator-1", roles = "OPERATOR")
  void cancelOrder_asOperator_cancelsOnlyOwnOrdersAndShowsPendingCancellation() throws Exception {
    Order inProgress = new Order();
    inProgress.setId("test-id");
    inProgress.setStatus(OrderStatus.IN_PROGRESS);
    inProgress.setRequestedByUserId("operator-1");
    inProgress.setItems(List.of());
    inProgress.setCreatedAt(Instant.parse("2026-10-18T10:00:00Z"));
    inProgress.setCancellationRequestedAt(Instant.parse("2026-10-18T10:05:00Z"));
    when(orderService.cancelOrder(eq("test-id"), isNull(), eq("operator-1"), eq(false)))
        .thenReturn(inProgress);

    mockMvc
        .perform(post("/orders/test-id/cancel"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.order.status").value("in_progress"))
        .andExpect(
            jsonPath("$.order.timestamps.cancellation_requested_at").value("2026-10-18T10:05:00Z"));
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
    when(orderService.cancelOrder(eq("test-id"), eq("Out of stock"), anyString(), eq(true)))
        .thenReturn(withReason);

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
    when(orderService.cancelOrder(anyString(), isNull(), anyString(), eq(true)))
        .thenReturn(sampleOrder);

    mockMvc.perform(post("/orders/test-id/cancel")).andExpect(status().isOk());
  }
}
