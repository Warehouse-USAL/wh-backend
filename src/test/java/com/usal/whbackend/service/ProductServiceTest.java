package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.ProductResponse;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.Zone;
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
    p.setCategory(ProductCategory.TECNOLOGIA.name());
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

    Page<ProductResponse> result = productService.getProducts("TECNOLOGIA", null, null, pageable);

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
                "SKU-001",
                "Widget",
                "A widget",
                "HERRAMIENTAS",
                null,
                null,
                null,
                10,
                5,
                10.0,
                10.0,
                10.0,
                1.0));

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
                        "SKU-001",
                        "Widget",
                        null,
                        "HERRAMIENTAS",
                        null,
                        null,
                        null,
                        10,
                        5,
                        10.0,
                        10.0,
                        10.0,
                        1.0)));
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
                        "SKU-002",
                        "Widget",
                        null,
                        "HERRAMIENTAS",
                        null,
                        null,
                        null,
                        10,
                        5,
                        10.0,
                        10.0,
                        10.0,
                        1.0)));
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
        "1",
        new UpdateProductRequest(
            "NewName", null, null, null, null, null, null, null, null, null, null, null, null));

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
                new UpdateProductRequest(
                    null, null, null, null, null, null, null, null, null, null, null, null, null)));
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

    verify(storageService).deleteByUrl("/api/v1/files/images/img1.jpg");
    verify(storageService).deleteByUrl("/api/v1/files/images/img2.jpg");
  }

  @Test
  void deleteProduct_withoutImages_doesNotCallStorage() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdIn(any())).thenReturn(List.of());

    productService.deleteProduct("1");

    verify(storageService, never()).deleteByUrl(any());
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

    var update =
        new UpdateProductRequest(
            null,
            null,
            null,
            List.of(
                new CreateProductRequest.ImageRequest("/api/v1/files/images/keep.jpg", null, true)),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null);

    productService.updateProduct("1", update);

    verify(storageService).deleteByUrl("/api/v1/files/images/remove.jpg");
    verify(storageService, never()).deleteByUrl("/api/v1/files/images/keep.jpg");
    verify(productRepository).save(any());
  }

  @Test
  void updateProduct_imagesNull_doesNotTouchStorage() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    var update =
        new UpdateProductRequest(
            "NewName", null, null, null, null, null, null, null, null, null, null, null, null);

    productService.updateProduct("1", update);

    verify(storageService, never()).deleteByUrl(any());
  }

  // ── INVALID_CATEGORY ───────────────────────────────────────────────────────

  @Test
  void createProduct_invalidCategory_throws400() {
    when(productRepository.findBySku("SKU-001")).thenReturn(Optional.empty());
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.createProduct(
                    new CreateProductRequest(
                        "SKU-001",
                        "Widget",
                        null,
                        "INVENTADO",
                        null,
                        null,
                        null,
                        0,
                        0,
                        10.0,
                        10.0,
                        10.0,
                        1.0)));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_CATEGORY", ex.getReason());
  }

  @Test
  void updateProduct_invalidCategory_throws400() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.updateProduct(
                    "1",
                    new UpdateProductRequest(
                        null,
                        null,
                        "INVENTADO",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_CATEGORY", ex.getReason());
  }

  @Test
  void getProducts_invalidCategory_throws400() {
    Pageable pageable = PageRequest.of(0, 10);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> productService.getProducts("INVENTADO", null, null, pageable));
    assertEquals(400, ex.getStatusCode().value());
    assertEquals("INVALID_CATEGORY", ex.getReason());
  }

  @Test
  void getProducts_validCategoryLowercase_filtersAndReturns() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result = productService.getProducts("tecnologia", null, null, pageable);

    assertEquals(1, result.getContent().size());
    verify(mongoTemplate)
        .count(
            argThat(query -> "TECNOLOGIA".equals(query.getQueryObject().get("category"))),
            eq(Product.class));
  }

  @Test
  void getProducts_searchAndCategoryCombined_filtersBoth() {
    Pageable pageable = PageRequest.of(0, 10);
    Product p = activeProduct("1");
    when(mongoTemplate.count(any(Query.class), eq(Product.class))).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroBulkReservedStock();

    Page<ProductResponse> result =
        productService.getProducts("HERRAMIENTAS", "widget", null, pageable);

    assertEquals(1, result.getContent().size());
    verify(mongoTemplate)
        .count(
            argThat(
                query -> {
                  var obj = query.getQueryObject();
                  return "HERRAMIENTAS".equals(obj.get("category")) && obj.containsKey("$or");
                }),
            eq(Product.class));
  }

  // ── getCategories ──────────────────────────────────────────────────────────

  @Test
  void getCategories_returnsEveryEnumValueInDeclarationOrder() {
    assertEquals(
        List.of("TECNOLOGIA", "HERRAMIENTAS", "ALIMENTOS", "OTROS"),
        productService.getCategories());
  }

  @Test
  void getCategories_matchesTheCategoriesAcceptedByCreate() {
    when(productRepository.findBySku(anyString())).thenReturn(Optional.empty());
    when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    for (String category : productService.getCategories()) {
      assertDoesNotThrow(
          () ->
              productService.createProduct(
                  new CreateProductRequest(
                      "SKU-" + category,
                      "Widget",
                      null,
                      category,
                      null,
                      null,
                      null,
                      null,
                      null,
                      1.0,
                      1.0,
                      1.0,
                      1.0)));
    }
  }

  // ── createProduct: nested collections and defaults ─────────────────────────

  @Test
  void createProduct_withImagesPriceAndSpecs_mapsThemOntoTheDocument() {
    when(productRepository.findBySku("SKU-010")).thenReturn(Optional.empty());
    when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    ProductResponse result =
        productService.createProduct(
            new CreateProductRequest(
                "SKU-010",
                "Widget",
                "desc",
                "tecnologia",
                List.of(new CreateProductRequest.ImageRequest("/img/a.jpg", "alt", true)),
                new CreateProductRequest.PriceRequest(1999L, "ARS", true),
                List.of(new CreateProductRequest.SpecRequest("Peso", "2kg")),
                7,
                3,
                10.0,
                20.0,
                30.0,
                1.5));

    org.mockito.ArgumentCaptor<Product> captor = org.mockito.ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    Product saved = captor.getValue();

    assertEquals("TECNOLOGIA", saved.getCategory());
    assertEquals(1, saved.getImages().size());
    assertEquals("/img/a.jpg", saved.getImages().get(0).getUrl());
    assertEquals("alt", saved.getImages().get(0).getAlt());
    assertTrue(saved.getImages().get(0).isPrimary());
    assertEquals(1999L, saved.getPrice().getAmountCents());
    assertEquals("ARS", saved.getPrice().getCurrency());
    assertTrue(saved.getPrice().isTaxIncluded());
    assertEquals(1, saved.getSpecs().size());
    assertEquals("Peso", saved.getSpecs().get(0).getLabel());
    assertEquals("2kg", saved.getSpecs().get(0).getValue());
    assertEquals(7, saved.getMaxQuantityPerOrder());
    assertEquals(3, saved.getMinimumStock());
    assertEquals(6000.0, saved.getVolume());
    assertTrue(saved.isActive());
    assertNotNull(saved.getCreatedAt());
    assertNotNull(result);
  }

  @Test
  void createProduct_nullOptionalFields_fallBackToZeroDefaults() {
    when(productRepository.findBySku("SKU-011")).thenReturn(Optional.empty());
    when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    productService.createProduct(
        new CreateProductRequest(
            "SKU-011", "Widget", null, "OTROS", null, null, null, null, null, null, null, null,
            null));

    org.mockito.ArgumentCaptor<Product> captor = org.mockito.ArgumentCaptor.forClass(Product.class);
    verify(productRepository).save(captor.capture());
    Product saved = captor.getValue();

    assertEquals(0, saved.getMaxQuantityPerOrder());
    assertEquals(0, saved.getMinimumStock());
    assertEquals(0.0, saved.getHeight());
    assertEquals(0.0, saved.getWidth());
    assertEquals(0.0, saved.getLength());
    assertEquals(0.0, saved.getWeight());
    assertNull(saved.getImages());
    assertNull(saved.getPrice());
    assertNull(saved.getSpecs());
  }

  @Test
  void createProduct_blankCategory_throws400() {
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () ->
                productService.createProduct(
                    new CreateProductRequest(
                        "SKU-012", "Widget", null, "   ", null, null, null, null, null, 1.0, 1.0,
                        1.0, 1.0)));
    assertEquals(400, ex.getStatusCode().value());
  }

  // ── updateProduct: full field sweep ────────────────────────────────────────

  @Test
  void updateProduct_allFields_areApplied() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    mockZeroSingleReservedStock();

    productService.updateProduct(
        "1",
        new UpdateProductRequest(
            "Renamed",
            "New description",
            "alimentos",
            List.of(new CreateProductRequest.ImageRequest("/img/new.jpg", "alt", false)),
            new CreateProductRequest.PriceRequest(500L, "USD", false),
            List.of(new CreateProductRequest.SpecRequest("Color", "Rojo")),
            9,
            4,
            false,
            2.0,
            3.0,
            4.0,
            5.0));

    assertEquals("Renamed", p.getName());
    assertEquals("New description", p.getDescription());
    assertEquals("ALIMENTOS", p.getCategory());
    assertEquals(1, p.getImages().size());
    assertEquals("/img/new.jpg", p.getImages().get(0).getUrl());
    assertEquals(500L, p.getPrice().getAmountCents());
    assertEquals("USD", p.getPrice().getCurrency());
    assertFalse(p.getPrice().isTaxIncluded());
    assertEquals(1, p.getSpecs().size());
    assertEquals("Color", p.getSpecs().get(0).getLabel());
    assertEquals(9, p.getMaxQuantityPerOrder());
    assertEquals(4, p.getMinimumStock());
    assertFalse(p.isActive());
    assertEquals(2.0, p.getHeight());
    assertEquals(3.0, p.getWidth());
    assertEquals(4.0, p.getLength());
    assertEquals(5.0, p.getWeight());
  }

  @Test
  void updateProduct_inactiveProduct_throws404() {
    Product p = activeProduct("1");
    p.setActive(false);
    when(productRepository.findById("1")).thenReturn(Optional.of(p));

    UpdateProductRequest req =
        new UpdateProductRequest(
            "x", null, null, null, null, null, null, null, null, null, null, null, null);
    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.updateProduct("1", req));
    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void updateProduct_imageDeletionFailure_isSwallowed() {
    Product p = activeProduct("1");
    Product.ProductImage img = new Product.ProductImage();
    img.setUrl("/api/v1/files/images/old.jpg");
    p.setImages(List.of(img));
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(positionRepository.findByProductIdInAndIsActiveTrue(any())).thenReturn(List.of());
    doThrow(new IllegalStateException("minio down"))
        .when(storageService)
        .deleteByUrl("/api/v1/files/images/old.jpg");
    mockZeroSingleReservedStock();

    assertDoesNotThrow(
        () ->
            productService.updateProduct(
                "1",
                new UpdateProductRequest(
                    null, null, null, List.of(), null, null, null, null, null, null, null, null,
                    null)));

    verify(storageService).deleteByUrl("/api/v1/files/images/old.jpg");
  }

  // ── deleteProduct cascade ──────────────────────────────────────────────────

  @Test
  void deleteProduct_clearsPositionAssignments() {
    Product p = activeProduct("1");
    Position occupied = new Position();
    occupied.setId("pos-1");
    occupied.setProductId("1");
    occupied.setCurrentStock(12);
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(productRepository.save(any())).thenReturn(p);
    when(positionRepository.findByProductIdIn(List.of("1"))).thenReturn(List.of(occupied));

    productService.deleteProduct("1");

    assertNull(occupied.getProductId());
    assertEquals(0, occupied.getCurrentStock());
    verify(positionRepository).save(occupied);
  }

  // ── getProductLocation ─────────────────────────────────────────────────────

  @Test
  void getProductLocation_denormalisesZoneAndLine() {
    Product p = activeProduct("1");
    Position pos = new Position();
    pos.setId("pos-1");
    pos.setPositionName("P01");
    pos.setCurrentStock(12);
    pos.setIdLine("l1");
    pos.setIdZone("z1");
    Line line = new Line();
    line.setId("l1");
    line.setNumberLine(2);
    Zone zone = new Zone();
    zone.setId("z1");
    zone.setZoneCode("A");

    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("1")))
        .thenReturn(List.of(pos));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of(line));
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of(zone));

    List<ProductService.ProductLocationEntry> locations = productService.getProductLocation("1");

    assertEquals(1, locations.size());
    ProductService.ProductLocationEntry entry = locations.get(0);
    assertEquals("pos-1", entry.idPosition());
    assertEquals("P01", entry.positionName());
    assertEquals(12, entry.currentStock());
    assertEquals("l1", entry.idLine());
    assertEquals(2, entry.numberLine());
    assertEquals("z1", entry.idZone());
    assertEquals("A", entry.zoneCode());
    assertEquals(
        entry, new ProductService.ProductLocationEntry("pos-1", "P01", 12, "l1", 2, "z1", "A"));
    assertTrue(entry.toString().contains("P01"));
  }

  @Test
  void getProductLocation_danglingLineAndZone_fallsBackToDefaults() {
    Product p = activeProduct("1");
    Position pos = new Position();
    pos.setId("pos-1");
    pos.setIdLine("l1");
    pos.setIdZone("z1");

    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("1")))
        .thenReturn(List.of(pos));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of());
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of());

    ProductService.ProductLocationEntry entry = productService.getProductLocation("1").get(0);

    assertEquals(0, entry.numberLine());
    assertNull(entry.zoneCode());
  }

  @Test
  void getProductLocation_noPositions_returnsEmptyList() {
    Product p = activeProduct("1");
    when(productRepository.findById("1")).thenReturn(Optional.of(p));
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("1"))).thenReturn(List.of());
    when(lineRepository.findAllById(List.of())).thenReturn(List.of());
    when(zoneRepository.findAllById(List.of())).thenReturn(List.of());

    assertTrue(productService.getProductLocation("1").isEmpty());
  }

  @Test
  void getProductLocation_unknownProduct_throws404() {
    when(productRepository.findById("ghost")).thenReturn(Optional.empty());
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class, () -> productService.getProductLocation("ghost"));
    assertEquals(404, ex.getStatusCode().value());
  }

  @Test
  void getProductLocation_inactiveProduct_throws404() {
    Product p = activeProduct("1");
    p.setActive(false);
    when(productRepository.findById("1")).thenReturn(Optional.of(p));

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> productService.getProductLocation("1"));
    assertEquals(404, ex.getStatusCode().value());
  }

  // ── stock computation ──────────────────────────────────────────────────────

  @Test
  void computeAvailableStock_sumsActivePositions() {
    Position a = new Position();
    a.setCurrentStock(4);
    Position b = new Position();
    b.setCurrentStock(6);
    when(positionRepository.findByProductIdInAndIsActiveTrue(List.of("1")))
        .thenReturn(List.of(a, b));

    assertEquals(10, productService.computeAvailableStock("1"));
  }

  @Test
  void computeReservedStock_noMatchingOrders_returnsZero() {
    mockZeroSingleReservedStock();

    assertEquals(0, productService.computeReservedStock("1"));
  }
}
