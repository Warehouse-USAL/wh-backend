package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.ProductResponse;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.storage.StorageService;
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
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

  @Mock ProductRepository productRepository;
  @Mock PositionRepository positionRepository;
  @Mock MongoTemplate mongoTemplate;
  @Mock LineRepository lineRepository;
  @Mock ZoneRepository zoneRepository;
  @Mock StorageService storageService;
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

  /** Mock for bulk reserved stock (getProducts path) — uses getMappedResults(). */
  @SuppressWarnings("unchecked")
  private void mockZeroBulkReservedStock() {
    AggregationResults<Object> emptyResults = mock(AggregationResults.class);
    when(emptyResults.getMappedResults()).thenReturn(List.of());
    when(mongoTemplate.aggregate(any(Aggregation.class), anyString(), any(Class.class)))
        .thenReturn((AggregationResults) emptyResults);
  }

  /**
   * Mock for single-product reserved stock (getProduct/updateProduct path) — uses
   * getUniqueMappedResult().
   */
  @SuppressWarnings("unchecked")
  private void mockZeroSingleReservedStock() {
    AggregationResults<Object> emptyResults = mock(AggregationResults.class);
    when(emptyResults.getUniqueMappedResult()).thenReturn(null);
    when(mongoTemplate.aggregate(any(Aggregation.class), anyString(), any(Class.class)))
        .thenReturn((AggregationResults) emptyResults);
  }

  // ── getProducts ────────────────────────────────────────────────────────────

  @Test
  void getProducts_noFilters_returnsActiveProducts() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts(null, null, null, pageable);

    assertEquals(1, result.getContent().size());
    assertEquals(1L, result.getTotalElements());
    assertTrue(result.getContent().get(0).active());
  }

  @Test
  void getProducts_categoryFilter_passesQueryToMongo() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts("electronics", null, null, pageable);

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
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts(null, "widget", null, pageable);

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
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts(null, null, false, pageable);

    assertEquals(1, result.getContent().size());
  }

  @Test
  void getProducts_secondPage_usesPageableFromArgument() {
    Pageable pageable = PageRequest.of(1, 5);
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(10L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(activeProduct("6"), activeProduct("7")));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts(null, null, null, pageable);

    assertEquals(2, result.getContent().size());
    assertEquals(10L, result.getTotalElements());
    assertEquals(1, result.getNumber());
  }

  // ── getProduct ─────────────────────────────────────────────────────────────

  @Test
  void getProduct_existingActiveProduct_returnsIt() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    assertEquals("1", productService.getProduct("1", null).id());
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

    ProductResponse result =
        productService.createProduct(
            new CreateProductRequest(
                "SKU-001", "Widget", "A widget", "tools", null, null, null, 10, 5));

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
                    new CreateProductRequest(
                        "SKU-001", "Widget", null, "tools", null, null, null, 10, 5)));
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
                    new CreateProductRequest(
                        "SKU-002", "Widget", null, "tools", null, null, null, 10, 5)));
    assertEquals(409, ex.getStatusCode().value());
  }

  // ── updateProduct ──────────────────────────────────────────────────────────

  @Test
  void updateProduct_existingProduct_updatesFields() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    productService.updateProduct(
        "1", new UpdateProductRequest("NewName", null, null, null, null, null, null, null, null));

    verify(productRepository).save(any());
  }

  @Test
  void updateProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(
        ResponseStatusException.class,
        () ->
            productService.updateProduct(
                "none",
                new UpdateProductRequest(null, null, null, null, null, null, null, null, null)));
  }

  // ── deleteProduct ──────────────────────────────────────────────────────────

  @Test
  void deleteProduct_existingProduct_setsActiveFalse() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdIn(any())).thenReturn(List.of());

    productService.deleteProduct("1");

    assertFalse(p.isActive());
    verify(productRepository).save(p);
  }

  @Test
  void deleteProduct_unknownId_throws404() {
    when(productRepository.findById("none")).thenReturn(Optional.empty());
    assertThrows(ResponseStatusException.class, () -> productService.deleteProduct("none"));
  }

  @Test
  void deleteProduct_withImages_deletesFromStorage() {
    Product p = activeProduct("1");
    Product.ProductImage img1 = new Product.ProductImage();
    img1.setUrl("/api/v1/files/images/img1.jpg");
    Product.ProductImage img2 = new Product.ProductImage();
    img2.setUrl("/api/v1/files/images/img2.jpg");
    p.setImages(List.of(img1, img2));

    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdIn(any())).thenReturn(List.of());

    productService.deleteProduct("1");

    verify(storageService).delete("images/img1.jpg");
    verify(storageService).delete("images/img2.jpg");
  }

  @Test
  void deleteProduct_withoutImages_doesNotCallStorage() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdIn(any())).thenReturn(List.of());

    productService.deleteProduct("1");

    verify(storageService, never()).delete(any());
  }

  // ── updateProduct cleanup ───────────────────────────────────────────

  @Test
  void updateProduct_removesImage_deletesOrphanFromStorage() {
    Product p = activeProduct("1");
    Product.ProductImage img1 = new Product.ProductImage();
    img1.setUrl("/api/v1/files/images/keep.jpg");
    Product.ProductImage img2 = new Product.ProductImage();
    img2.setUrl("/api/v1/files/images/remove.jpg");
    p.setImages(List.of(img1, img2));

    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    var update = new UpdateProductRequest(
        null, null, null,
        List.of(new CreateProductRequest.ImageRequest("/api/v1/files/images/keep.jpg", null, true)),
        null, null, null, null, null);

    productService.updateProduct("1", update);

    verify(storageService).delete("images/remove.jpg");
    verify(storageService, never()).delete("images/keep.jpg");
    verify(productRepository).save(any());
  }

  @Test
  void updateProduct_imagesNull_doesNotTouchStorage() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    var update = new UpdateProductRequest(
        "NewName", null, null, null, null, null, null, null, null);

    productService.updateProduct("1", update);

    verify(storageService, never()).delete(any());
  }
}
