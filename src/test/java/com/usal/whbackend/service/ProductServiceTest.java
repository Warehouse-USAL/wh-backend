package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
  class ProductServiceTest {

    @Mock ProductRepository productRepository;
        @InjectMocks ProductService productService;

    // ── getProducts ────────────────────────────────────────────────────────

    @Test
        void getProducts_sinFiltros_devuelveTodos() {
                  Product p1 = new Product();
                  Product p2 = new Product();
                  when(productRepository.findAll()).thenReturn(List.of(p1, p2));

            List<Product> result = productService.getProducts(null, null, null);

            assertEquals(2, result.size());
        }

    @Test
        void getProducts_conCategory_filtraPorCategory() {
                  Product p = new Product();
                  p.setCategory("electronics");
                  when(productRepository.findByCategory("electronics")).thenReturn(List.of(p));

            List<Product> result = productService.getProducts("electronics", null, null);

            assertEquals(1, result.size());
                  assertEquals("electronics", result.get(0).getCategory());
        }

    @Test
        void getProducts_conSearch_filtraPorNombreYSku() {
                  Product p1 = new Product();
                  p1.setName("laptop gamer");
                  p1.setSku("LPT-001");

            Product p2 = new Product();
                  p2.setName("mouse");
                  p2.setSku("MSE-001");

            when(productRepository.findAll()).thenReturn(List.of(p1, p2));

            List<Product> result = productService.getProducts(null, "laptop", null);

            assertEquals(1, result.size());
                  assertEquals("laptop gamer", result.get(0).getName());
        }

    @Test
        void getProducts_conActive_filtraPorActivos() {
                  Product p1 = new Product();
                  p1.setActive(true);
                  Product p2 = new Product();
                  p2.setActive(false);
                  when(productRepository.findByActive(true)).thenReturn(List.of(p1));

            List<Product> result = productService.getProducts(null, null, true);

            assertEquals(1, result.size());
                  assertTrue(result.get(0).isActive());
        }

    // ── getProduct ─────────────────────────────────────────────────────────

    @Test
        void getProduct_existente_devuelveProducto() {
                  Product product = new Product();
                  product.setSku("SKU-001");
                  when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

            Product result = productService.getProduct("prod-1");

            assertEquals("SKU-001", result.getSku());
        }

    @Test
        void getProduct_inexistente_lanza404() {
                  when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.getProduct("no-existe"));

            assertEquals(404, ex.getStatusCode().value());
                  assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
        }

    // ── createProduct ──────────────────────────────────────────────────────

    @Test
        void createProduct_valido_creaProducto() {
                  when(productRepository.findBySku("SKU-001")).thenReturn(Optional.empty());
                  when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateProductRequest request = new CreateProductRequest(
                              "SKU-001", "Test Product", "A description", "electronics", null);

            Product result = productService.createProduct(request);

            assertEquals("SKU-001", result.getSku());
                  assertEquals("Test Product", result.getName());
                  assertTrue(result.isActive());
                  assertEquals(0, result.getAvailableStock());
                  assertNotNull(result.getCreatedAt());
                  verify(productRepository).save(any(Product.class));
        }

    @Test
        void createProduct_skuDuplicado_lanza400() {
                  Product existing = new Product();
                  when(productRepository.findBySku("SKU-DUP")).thenReturn(Optional.of(existing));

            CreateProductRequest request = new CreateProductRequest(
                              "SKU-DUP", "Product", null, "category", null);

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.createProduct(request));

            assertEquals(400, ex.getStatusCode().value());
                  assertEquals("SKU_ALREADY_EXISTS", ex.getReason());
        }

    @Test
        void createProduct_skuVacio_lanza400() {
                  CreateProductRequest request = new CreateProductRequest(
                                    "", "Product", null, "category", null);

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.createProduct(request));

            assertEquals(400, ex.getStatusCode().value());
                  assertEquals("MISSING_REQUIRED_FIELDS", ex.getReason());
        }

    @Test
        void createProduct_nameFaltante_lanza400() {
                  CreateProductRequest request = new CreateProductRequest(
                                    "SKU-001", null, null, "category", null);

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.createProduct(request));

            assertEquals(400, ex.getStatusCode().value());
                  assertEquals("MISSING_REQUIRED_FIELDS", ex.getReason());
        }

    @Test
        void createProduct_categoryFaltante_lanza400() {
                  CreateProductRequest request = new CreateProductRequest(
                                    "SKU-001", "Product", null, null, null);

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.createProduct(request));

            assertEquals(400, ex.getStatusCode().value());
                  assertEquals("MISSING_REQUIRED_FIELDS", ex.getReason());
        }

    // ── updateProduct ──────────────────────────────────────────────────────

    @Test
        void updateProduct_existente_actualizaCampos() {
                  Product product = new Product();
                  product.setId("prod-1");
                  product.setSku("SKU-001");
                  product.setName("Old Name");
                  when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));
                  when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            UpdateProductRequest request = new UpdateProductRequest("New Name", null, null, null);
                  Product result = productService.updateProduct("prod-1", request);

            assertEquals("New Name", result.getName());
                  assertEquals("SKU-001", result.getSku()); // SKU no debe cambiar
            verify(productRepository).save(any(Product.class));
        }

    @Test
        void updateProduct_inexistente_lanza404() {
                  when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class,
                              () -> productService.updateProduct("no-existe", new UpdateProductRequest(null, null, null, null)));

            assertEquals(404, ex.getStatusCode().value());
                  assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
        }

    // ── deleteProduct ──────────────────────────────────────────────────────

    @Test
        void deleteProduct_existente_setaActiveFalse() {
                  Product product = new Product();
                  product.setId("prod-1");
                  product.setActive(true);
                  when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));
                  when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

            productService.deleteProduct("prod-1");

            assertFalse(product.isActive());
                  verify(productRepository).save(product);
        }

    @Test
        void deleteProduct_inexistente_lanza404() {
                  when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(
                              ResponseStatusException.class, () -> productService.deleteProduct("no-existe"));

            assertEquals(404, ex.getStatusCode().value());
                  assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
        }
  }
