package com.usal.whbackend.api.product;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Product;
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

    @Test
    @WithMockUser(roles = "PROVIDER")
    void createProduct_withUnauthorizedRole_returns403() throws Exception {
        mockMvc.perform(post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\",\"description\":\"\",\"zoneId\":\"A\",\"line\":\"1\",\"position\":\"1\",\"height\":\"1\",\"maxQuantityPerOrder\":10}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_WAREHOUSE")
    void createProduct_withAdminWarehouse_returns201() throws Exception {
        when(productService.createProduct(any())).thenReturn(new Product());
        mockMvc.perform(post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\",\"description\":\"\",\"zoneId\":\"A\",\"line\":\"1\",\"position\":\"1\",\"height\":\"1\",\"maxQuantityPerOrder\":10}"))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void deleteProduct_withAdminSales_returns403() throws Exception {
        mockMvc.perform(delete("/products/prod-1"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_WAREHOUSE")
    void deleteProduct_withAdminWarehouse_returns204() throws Exception {
        doNothing().when(productService).deleteProduct(anyString());
        mockMvc.perform(delete("/products/prod-1"))
            .andExpect(status().isNoContent());
    }

    // ── SUPERADMIN: should have full access to all product endpoints ───────

    @Test
    @WithMockUser(roles = "SUPERADMIN")
    void createProduct_withSuperadmin_returns201() throws Exception {
        when(productService.createProduct(any())).thenReturn(new Product());
        mockMvc.perform(post("/products")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sku\":\"SKU-001\",\"name\":\"Test\",\"category\":\"electronics\"}"))
            .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "SUPERADMIN")
    void updateProduct_withSuperadmin_returns200() throws Exception {
        when(productService.updateProduct(anyString(), any())).thenReturn(new Product());
        mockMvc.perform(patch("/products/prod-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Updated Name\"}"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SUPERADMIN")
    void deleteProduct_withSuperadmin_returns204() throws Exception {
        doNothing().when(productService).deleteProduct(anyString());
        mockMvc.perform(delete("/products/prod-1"))
            .andExpect(status().isNoContent());
    }
}
