package com.usal.whbackend.api.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean ProductService productService;
  @MockitoBean JwtService jwtService;

  private Product sampleProduct;

  @BeforeEach
  void setUp() {
    sampleProduct = new Product();
    sampleProduct.setId("prod-1");
    sampleProduct.setSku("SKU-001");
    sampleProduct.setName("Test Product");
    sampleProduct.setCategory("electronics");
    sampleProduct.setActive(true);
    sampleProduct.setAvailableStock(10);
    sampleProduct.setReservedStock(0);
  }

  @Test
  @WithMockUser
  void getProducts_returns200() throws Exception {
    when(productService.getProducts(any(), any(), any()))
        .thenReturn(java.util.List.of(sampleProduct));
    mockMvc
        .perform(get("/products"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.products").isArray());
  }

  @Test
  @WithMockUser
  void getProduct_returns200() throws Exception {
    when(productService.getProduct(anyString(), any())).thenReturn(sampleProduct);
    mockMvc
        .perform(get("/products/prod-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product.sku").value("SKU-001"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createProduct_returns201() throws Exception {
    when(productService.createProduct(any())).thenReturn(sampleProduct);
    mockMvc
        .perform(
            post("/products")
                .contentType("application/json")
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.product").exists())
        .andExpect(jsonPath("$.product.sku").value("SKU-001"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void updateProduct_returns200() throws Exception {
    when(productService.updateProduct(anyString(), any())).thenReturn(sampleProduct);
    mockMvc
        .perform(
            patch("/products/prod-1")
                .contentType("application/json")
                .content("{\"name\":\"Updated\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product").exists());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteProduct_returns204() throws Exception {
    doNothing().when(productService).deleteProduct(anyString());
    mockMvc.perform(delete("/products/prod-1")).andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser
  void getProduct_responseBodyUsesSnakeCaseKeys() throws Exception {
    // Regression: API must serialize responses with snake_case field names so that
    // consumers sending image_url / available_stock / max_quantity_per_order / minimum_stock
    // receive them back under the same snake_case keys.
    Product product = new Product();
    product.setId("prod-snake");
    product.setSku("SKU-SNAKE");
    product.setName("Snake Product");
    product.setCategory("electronics");
    product.setActive(true);
    product.setImageUrl("https://example.com/img.png");
    product.setAvailableStock(50);
    product.setMaxQuantityPerOrder(5);
    product.setMinimumStock(10);
    product.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));

    when(productService.getProduct(anyString(), any())).thenReturn(product);

    mockMvc
        .perform(get("/products/prod-snake"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.product.image_url").value("https://example.com/img.png"))
        .andExpect(jsonPath("$.product.created_at").exists())
        .andExpect(jsonPath("$.product.stock.minimum_stock").value(10))
        .andExpect(jsonPath("$.product.order_constraints.max_quantity_per_order").value(5))
        .andExpect(jsonPath("$.product.stock.available").value(50));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createProduct_acceptsSnakeCaseRequestBody() throws Exception {
    // Regression: fields sent as snake_case must be deserialized, not silently dropped.
    Product stored = new Product();
    stored.setId("prod-sc");
    stored.setSku("SKU-SC");
    stored.setName("Snake Create");
    stored.setCategory("tools");
    stored.setImageUrl("https://example.com/tool.png");
    stored.setAvailableStock(100);
    stored.setMaxQuantityPerOrder(10);
    stored.setMinimumStock(20);
    stored.setActive(true);
    stored.setCreatedAt(java.time.Instant.parse("2026-01-01T00:00:00Z"));

    when(productService.createProduct(any())).thenReturn(stored);

    mockMvc
        .perform(
            post("/products")
                .contentType("application/json")
                .content(
                    "{\"sku\":\"SKU-SC\",\"name\":\"Snake Create\",\"category\":\"tools\","
                        + "\"image_url\":\"https://example.com/tool.png\","
                        + "\"available_stock\":100,"
                        + "\"max_quantity_per_order\":10,"
                        + "\"minimum_stock\":20}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.product.image_url").value("https://example.com/tool.png"))
        .andExpect(jsonPath("$.product.stock.available").value(100))
        .andExpect(jsonPath("$.product.order_constraints.max_quantity_per_order").value(10));
  }
}
