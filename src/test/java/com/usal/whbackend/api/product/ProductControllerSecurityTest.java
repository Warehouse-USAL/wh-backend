package com.usal.whbackend.api.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.ProductService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class ProductControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean ProductService productService;
  @MockitoBean JwtService jwtService;

  private static ProductResponse emptyProductResponse() {
    return new ProductResponse(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new ProductResponse.Stock(0, 0, 0),
        new ProductResponse.OrderConstraints(0),
        true,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void createProduct_withUnauthorizedRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\",\"description\":\"\",\"zoneId\":\"A\",\"line\":\"1\",\"position\":\"1\",\"height\":\"1\",\"width\":\"1\",\"length\":\"1\",\"weight\":\"1\",\"maxQuantityPerOrder\":10}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void createProduct_withAdminWarehouse_returns201() throws Exception {
    when(productService.createProduct(any())).thenReturn(emptyProductResponse());
    mockMvc
        .perform(
            post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\",\"description\":\"\",\"zoneId\":\"A\",\"line\":\"1\",\"position\":\"1\",\"height\":\"1\",\"width\":\"1\",\"length\":\"1\",\"weight\":\"1\",\"maxQuantityPerOrder\":10}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void deleteProduct_withAdminSales_returns403() throws Exception {
    mockMvc.perform(delete("/products/prod-1")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void deleteProduct_withAdminWarehouse_returns204() throws Exception {
    doNothing().when(productService).deleteProduct(anyString());
    mockMvc.perform(delete("/products/prod-1")).andExpect(status().isNoContent());
  }

  // ── SUPERADMIN: should have full access to all product endpoints ───────

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void createProduct_withSuperadmin_returns201() throws Exception {
    when(productService.createProduct(any())).thenReturn(emptyProductResponse());
    mockMvc
        .perform(
            post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\",\"height\":1.0,\"width\":1.0,\"length\":1.0,\"weight\":1.0}"))
        .andExpect(status().isCreated());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void updateProduct_withSuperadmin_returns200() throws Exception {
    when(productService.updateProduct(anyString(), any())).thenReturn(emptyProductResponse());
    mockMvc
        .perform(
            patch("/products/prod-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated Name\"}"))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void deleteProduct_withSuperadmin_returns204() throws Exception {
    doNothing().when(productService).deleteProduct(anyString());
    mockMvc.perform(delete("/products/prod-1")).andExpect(status().isNoContent());
  }

  @Test
  @WithMockUser(roles = "OPERATOR")
  void getCategories_isReadableByAnyAuthenticatedRole() throws Exception {
    when(productService.getCategories()).thenReturn(java.util.List.of("TECNOLOGIA"));
    mockMvc.perform(get("/products/categories")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "PROVIDER")
  void getProductLocation_isReadableByAnyAuthenticatedRole() throws Exception {
    when(productService.getProductLocation("prod-1")).thenReturn(java.util.List.of());
    mockMvc.perform(get("/products/prod-1/location")).andExpect(status().isOk());
  }
}
