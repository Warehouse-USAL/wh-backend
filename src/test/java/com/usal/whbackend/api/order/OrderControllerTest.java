package com.usal.whbackend.api.order;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(OrderController.class)
@Import(SecurityConfig.class)
class OrderControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean OrderService orderService;

  @Test
  void getOrders_returns200() throws Exception {
    mockMvc.perform(get("/orders")).andExpect(status().isOk());
  }

  @Test
  void getOrder_returns200() throws Exception {
    mockMvc.perform(get("/orders/test-id")).andExpect(status().isOk());
  }

  @Test
  void createOrder_returns200() throws Exception {
    mockMvc
        .perform(
            post("/orders")
                .contentType("application/json")
                .content("{\"items\":[],\"destinationArea\":\"AREA-A\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void cancelOrder_returns200() throws Exception {
    mockMvc.perform(post("/orders/test-id/cancel")).andExpect(status().isOk());
  }
}
