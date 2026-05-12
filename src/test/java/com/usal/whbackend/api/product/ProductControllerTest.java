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

import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
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
        void getProducts_returns200() throws Exception {
                  when(productService.getProducts(any(), any(), any()))
                                    .thenReturn(java.util.List.of(sampleProduct));
                  mockMvc.perform(get("/products"))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.products").isArray());
        }

    @Test
        void getProduct_returns200() throws Exception {
                  when(productService.getProduct(anyString(), any())).thenReturn(sampleProduct);
                  mockMvc.perform(get("/products/prod-1"))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.product.sku").value("SKU-001"));
        }

    @Test
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
        void deleteProduct_returns204() throws Exception {
                  doNothing().when(productService).deleteProduct(anyString());
                  mockMvc.perform(delete("/products/prod-1")).andExpect(status().isNoContent());
        }
  }
