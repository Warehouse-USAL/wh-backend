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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository productRepository;
  @Mock MongoTemplate mongoTemplate;
  @InjectMocks ProductService productService;

  private Product activeProduct(String id) {
    Product p = new Product();
    p.setId(id);
    p.setName("Product " + id);
    p.setSku("SKU-" + id);
    p.setCategory("electronics");
    p.setActive(true);
    return p;
  }

  // ── getProducts ────────────────────────────────────────────────────────────

  @Test
  void getProducts_noFilters_returnsActiveProducts() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));

    Page<Product> result = productService.getProducts(null, null, null, pageable);

    assertEquals(1, result.getContent().size());
    assertEquals(1L, result.getTotalElements());
    assertTrue(result.getContent().get(0).isActive());
  }

  @Test
  void getProducts_categoryFilter_passesQueryToMongo() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));

    Page<Product> result = productService.getProducts("electronics", null, null, pageable);

    assertEquals(1, result.getContent().size());
    verify(mongoTemplate).count(any(Query.class), eq(Product.class));
    verify(mongoTemplate).find(any(Query.class), eq(Product.class));
  }

  @Test
  void getProducts_searchFilter_queriesMongoNotInMemory() {
    Pageable pageable = PageRequest.of(0, 10);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(activeProduct("1")));

    Page<Product> result = productService.getProducts(null, "widget", null, pageable);

    // MongoDB does the filtering — mongoTemplate.find is called (not in-memory filtering)
    verify(mongoTemplate).find(any(Query.class), eq(Product.class));
    assertEquals(1, result.getContent().size());
  }

  @Test
  void getProducts_inactiveFilter_queriesMongo() {
    Pageable pageable = PageRequest.of(0, 10);
    Product inactive = new Product();
    inactive.setActive(false);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(inactive));

    Page<Product> result = productService.getProducts(null, null, false, pageable);

    assertEquals(1, result.getContent().size());
  }

  @Test
  void getProducts_secondPage_usesPageableFromArgument() {
    Pageable pageable = PageRequest.of(1, 5);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(10L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(activeProduct("6"), activeProduct("7")));

    Page<Product> result = productService.getProducts(null, null, null, pageable);

    assertEquals(2, result.getContent().size());
    assertEquals(10L, result.getTotalElements());
    assertEquals(1, result.getNumber());
  }

  // ── getProduct ─────────────────────────────────────────────────────────────

  @Test
  void getProduct_existingActiveProduct_returnsIt() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    assertEquals("1", productService.getProduct("1", null).getId());
  }

  @Test
  void getProduct_inactiveWithNullIsActive_throws404() {
    Product p = new Product();
    p.setActive(false);
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    assertThrows(ResponseStatusException.class, () -> productService.getProduct("1", null));
  }

  @Test
  void getProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> productService.getProduct("none", null));
  }

  // ── createProduct ──────────────────────────────────────────────────────────

  @Test
  void createProduct_validRequest_savesAndReturns() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.empty());
    Product saved = activeProduct("new");
    when(productRepository.save(any())).thenReturn(saved);

    Product result =
        productService.createProduct(
            new CreateProductRequest("SKU-001", "Widget", "A widget", "tools", null, 10, 5));

    assertNotNull(result);
    verify(productRepository).save(any());
  }

  @Test
  void createProduct_duplicateSku_throws409() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.of(activeProduct("1")));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.createProduct(
                    new CreateProductRequest("SKU-001", "Widget", null, "tools", null, 10, 5)));
    assertEquals(409, ex.getStatusCode().value());
  }

  @Test
  void createProduct_duplicateKeyExceptionFromDb_throws409() {
    when(productRepository.findBySku(any())).thenReturn(Optional.empty());
    when(productRepository.save(any())).thenThrow(new DuplicateKeyException("dup"));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.createProduct(
                    new CreateProductRequest("SKU-002", "Widget", null, "tools", null, 10, 5)));
    assertEquals(409, ex.getStatusCode().value());
  }

  // ── updateProduct ──────────────────────────────────────────────────────────

  @Test
  void updateProduct_existingProduct_updatesFields() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);

    productService.updateProduct(
        "1", new UpdateProductRequest("NewName", null, null, null, null, null, null));

    verify(productRepository).save(any());
  }

  @Test
  void updateProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.updateProduct(
                "none", new UpdateProductRequest(null, null, null, null, null, null, null)));
  }

  // ── deleteProduct ──────────────────────────────────────────────────────────

  @Test
  void deleteProduct_existingProduct_setsActiveFalse() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);

    productService.deleteProduct("1");

    assertFalse(p.isActive());
    verify(productRepository).save(p);
  }

  @Test
  void deleteProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> productService.deleteProduct("none"));
  }
}
