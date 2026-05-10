package com.usal.whbackend.api.product;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean ProductService productService;

  @Test
  void getProducts_returns200() throws Exception {
    mockMvc.perform(get("/products")).andExpect(status().isOk());
  }

  @Test
  void getProduct_returns200() throws Exception {
    mockMvc.perform(get("/products/test-id")).andExpect(status().isOk());
  }

  @Test
  void createProduct_returns200() throws Exception {
    mockMvc
        .perform(
            post("/products")
                .contentType("application/json")
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void updateProduct_returns200() throws Exception {
    mockMvc
        .perform(
            patch("/products/test-id")
                .contentType("application/json")
                .content("{\"name\":\"Updated\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void deleteProduct_returns204() throws Exception {
    mockMvc.perform(delete("/products/test-id")).andExpect(status().isNoContent());
  }
}
