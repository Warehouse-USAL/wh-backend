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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository productRepository;
  @InjectMocks ProductService productService;

  // ── getProducts ────────────────────────────────────────────────────────

  @Test
  void getProducts_sinFiltros_devuelveSoloActivos() {
    Product p1 = new Product();
    p1.setActive(true);
    Product p2 = new Product();
    p2.setActive(true);
    when(productRepository.findByActive(true)).thenReturn(List.of(p1, p2));

    List<Product> result = productService.getProducts(null, null, null);

    assertEquals(2, result.size());
    verify(productRepository).findByActive(true);
    verify(productRepository, never()).findAll();
  }

  @Test
  void getProducts_conCategory_filtraPorCategoryYActivos() {
    Product p = new Product();
    p.setCategory("electronics");
    p.setActive(true);
    when(productRepository.findByCategoryAndActive("electronics", true)).thenReturn(List.of(p));

    List<Product> result = productService.getProducts("electronics", null, null);

    assertEquals(1, result.size());
    assertEquals("electronics", result.get(0).getCategory());
    verify(productRepository).findByCategoryAndActive("electronics", true);
    verify(productRepository, never()).findByCategory(any());
  }

  @Test
  void getProducts_conCategoryYActive_filtraPorAmbos() {
    Product p = new Product();
    p.setCategory("electronics");
    p.setActive(false);
    when(productRepository.findByCategoryAndActive("electronics", false)).thenReturn(List.of(p));

    List<Product> result = productService.getProducts("electronics", null, false);

    assertEquals(1, result.size());
    verify(productRepository).findByCategoryAndActive("electronics", false);
  }

  @Test
  void getProducts_conSearch_filtraPorNombreYSku() {
    Product p1 = new Product();
    p1.setName("laptop gamer");
    p1.setSku("LPT-001");
    p1.setActive(true);

    Product p2 = new Product();
    p2.setName("mouse");
    p2.setSku("MSE-001");
    p2.setActive(true);

    when(productRepository.findByActive(true)).thenReturn(List.of(p1, p2));

    List<Product> result = productService.getProducts(null, "laptop", null);

    assertEquals(1, result.size());
    assertEquals("laptop gamer", result.get(0).getName());
  }

  @Test
  void getProducts_conActive_filtraPorActivos() {
    Product p1 = new Product();
    p1.setActive(true);
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
    product.setActive(true);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    Product result = productService.getProduct("prod-1", null);

    assertEquals("SKU-001", result.getSku());
  }

  @Test
  void getProduct_inexistente_lanza404() {
    when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> productService.getProduct("no-existe", null));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }

  @Test
  void getProduct_softDeleted_lanza404() {
    Product product = new Product();
    product.setActive(false);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> productService.getProduct("prod-1", null));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }

  @Test
  void getProduct_softDeleted_conIsActiveFalse_devuelveProducto() {
    Product product = new Product();
    product.setSku("SKU-001");
    product.setActive(false);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    Product result = productService.getProduct("prod-1", false);

    assertEquals("SKU-001", result.getSku());
    assertFalse(result.isActive());
  }

  // ── createProduct ──────────────────────────────────────────────────────

  @Test
  void createProduct_valido_creaProducto() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.empty());
    when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-001",
            "Test Product",
            "A description",
            "electronics",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    Product result = productService.createProduct(request);

    assertEquals("SKU-001", result.getSku());
    assertEquals("Test Product", result.getName());
    assertTrue(result.isActive());
    assertEquals(0, result.getAvailableStock());
    assertNotNull(result.getCreatedAt());
    verify(productRepository).save(any(Product.class));
  }

  @Test
  void createProduct_conTodosLosCamposOpcionales_losGuardaCorrectamente() {
    // Regression: fields imageUrl/availableStock/maxQuantityPerOrder/minimumStock must NOT
    // be silently lost when the consumer sends them with the correct camelCase field names.
    when(productRepository.findBySku("SKU-FULL")).thenReturn(Optional.empty());
    when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-FULL",
            "Full Product",
            "Description",
            "safety",
            "https://example.com/img.png",
            100,
            10,
            20,
            "A",
            "3",
            "B",
            "2");

    Product result = productService.createProduct(request);

    assertEquals(100, result.getAvailableStock(), "availableStock must be stored, not defaulted to 0");
    assertEquals(10, result.getMaxQuantityPerOrder(), "maxQuantityPerOrder must be stored, not defaulted to 0");
    assertEquals(20, result.getMinimumStock(), "minimumStock must be stored, not defaulted to 0");
    assertEquals("https://example.com/img.png", result.getImageUrl(), "imageUrl must be stored, not null");
    assertEquals("A", result.getZone());
    assertEquals("3", result.getLine());
    assertEquals("B", result.getPosition());
    assertEquals("2", result.getHeight());
  }

  @Test
  void createProduct_skuDuplicado_lanza400() {
    Product existing = new Product();
    when(productRepository.findBySku("SKU-DUP")).thenReturn(Optional.of(existing));

    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-DUP", "Product", null, "category", null, null, null, null, null, null, null, null);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.createProduct(request));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("SKU_ALREADY_EXISTS", ex.getReason());
  }

  @Test
  void createProduct_duplicateKeyExceptionEnSave_lanza400() {
    when(productRepository.findBySku("SKU-RACE")).thenReturn(Optional.empty());
    when(productRepository.save(any(Product.class)))
        .thenThrow(new DuplicateKeyException("duplicate key"));

    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-RACE", "Product", null, "category", null, null, null, null, null, null, null, null);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.createProduct(request));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("SKU_ALREADY_EXISTS", ex.getReason());
  }

  @Test
  void createProduct_skuVacio_lanza400() {
    CreateProductRequest request =
        new CreateProductRequest(
            "", "Product", null, "category", null, null, null, null, null, null, null, null);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.createProduct(request));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("MISSING_REQUIRED_FIELDS", ex.getReason());
  }

  @Test
  void createProduct_nameFaltante_lanza400() {
    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-001", null, null, "category", null, null, null, null, null, null, null, null);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.createProduct(request));

    assertEquals(400, ex.getStatusCode().value());
    assertEquals("MISSING_REQUIRED_FIELDS", ex.getReason());
  }

  @Test
  void createProduct_categoryFaltante_lanza400() {
    CreateProductRequest request =
        new CreateProductRequest(
            "SKU-001", "Product", null, null, null, null, null, null, null, null, null, null);

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.createProduct(request));

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
    product.setActive(true);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));
    when(productRepository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

    UpdateProductRequest request =
        new UpdateProductRequest("New Name", null, null, null, null, null, null, null, null, null);
    Product result = productService.updateProduct("prod-1", request);

    assertEquals("New Name", result.getName());
    assertEquals("SKU-001", result.getSku()); // SKU no debe cambiar
    verify(productRepository).save(any(Product.class));
  }

  @Test
  void updateProduct_inexistente_lanza404() {
    when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.updateProduct(
                    "no-existe",
                    new UpdateProductRequest(
                        null, null, null, null, null, null, null, null, null, null)));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }

  @Test
  void updateProduct_softDeleted_lanza404() {
    Product product = new Product();
    product.setId("prod-1");
    product.setActive(false);
    when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.updateProduct(
                    "prod-1",
                    new UpdateProductRequest(
                        "New Name", null, null, null, null, null, null, null, null, null)));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
    verify(productRepository, never()).save(any());
  }

  // ── deleteProduct ──────────────────────────────────────────────────────

  @Test
  void deleteProduct_existente_setActiveFalse() {
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

    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> productService.deleteProduct("no-existe"));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }
}
