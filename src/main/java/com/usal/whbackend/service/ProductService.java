package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.ProductResponse;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ZoneRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final PositionRepository positionRepository;
  private final MongoTemplate mongoTemplate;
  private final LineRepository lineRepository;
  private final ZoneRepository zoneRepository;

  public ProductService(
      ProductRepository productRepository,
      PositionRepository positionRepository,
      MongoTemplate mongoTemplate,
      LineRepository lineRepository,
      ZoneRepository zoneRepository) {
    this.productRepository = productRepository;
    this.positionRepository = positionRepository;
    this.mongoTemplate = mongoTemplate;
    this.lineRepository = lineRepository;
    this.zoneRepository = zoneRepository;
  }

  // ── Stock computation ──────────────────────────────────────────────────────

  public int computeAvailableStock(String productId) {
    return positionRepository.findByProductIdInAndIsActiveTrue(List.of(productId)).stream()
        .mapToInt(Position::getCurrentStock)
        .sum();
  }

  public int computeReservedStock(String productId) {
    var agg =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("status").in("PENDING", "IN_PROGRESS")),
            Aggregation.unwind("items"),
            Aggregation.match(Criteria.where("items.productId").is(productId)),
            Aggregation.group().sum("items.quantity").as("total"));
    AggregationResults<StockSum> results = mongoTemplate.aggregate(agg, "orders", StockSum.class);
    StockSum sum = results.getUniqueMappedResult();
    return sum != null ? sum.total() : 0;
  }

  private Map<String, Integer> bulkAvailableStock(List<String> productIds) {
    return positionRepository.findByProductIdInAndIsActiveTrue(productIds).stream()
        .collect(
            Collectors.groupingBy(
                Position::getProductId, Collectors.summingInt(Position::getCurrentStock)));
  }

  private Map<String, Integer> bulkReservedStock(List<String> productIds) {
    var agg =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("status").in("PENDING", "IN_PROGRESS")),
            Aggregation.unwind("items"),
            Aggregation.match(Criteria.where("items.productId").in(productIds)),
            Aggregation.group("items.productId").sum("items.quantity").as("total"));
    AggregationResults<ProductStockSum> results =
        mongoTemplate.aggregate(agg, "orders", ProductStockSum.class);
    return results.getMappedResults().stream()
        .collect(Collectors.toMap(ProductStockSum::id, ProductStockSum::total));
  }

  // ── Product CRUD ───────────────────────────────────────────────────────────

  private void validateCategory(String category) {
    try {
      ProductCategory.valueOf(category);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY");
    }
  }

  public Page<ProductResponse> getProducts(
      String category, String search, Boolean active, Pageable pageable) {
    Query query = new Query();
    query.addCriteria(Criteria.where("active").is(active != null ? active : true));
    if (category != null) {
      validateCategory(category);
      query.addCriteria(Criteria.where("category").is(category));
    }
    if (search != null && !search.isBlank()) {
      query.addCriteria(
          new Criteria()
              .orOperator(
                  Criteria.where("name").regex(search, "i"),
                  Criteria.where("sku").regex(search, "i")));
    }
    long total = mongoTemplate.count(query, Product.class);
    List<Product> items = mongoTemplate.find(query.with(pageable), Product.class);

    List<String> ids = items.stream().map(Product::getId).toList();
    Map<String, Integer> available = bulkAvailableStock(ids);
    Map<String, Integer> reserved = bulkReservedStock(ids);

    List<ProductResponse> responses =
        items.stream()
            .map(
                p ->
                    ProductResponse.from(
                        p,
                        available.getOrDefault(p.getId(), 0),
                        reserved.getOrDefault(p.getId(), 0)))
            .toList();
    return new PageImpl<>(responses, pageable, total);
  }

  public ProductResponse getProduct(String id, Boolean isActive) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!Boolean.FALSE.equals(isActive) && !product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    return ProductResponse.from(product, computeAvailableStock(id), computeReservedStock(id));
  }

  public ProductResponse createProduct(CreateProductRequest request) {
    if (productRepository.findBySku(request.sku()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }
    validateCategory(request.category());
    Product product = new Product();
    product.setSku(request.sku());
    product.setName(request.name());
    product.setDescription(request.description());
    product.setCategory(request.category());
    if (request.images() != null) {
      product.setImages(
          request.images().stream()
              .filter(java.util.Objects::nonNull)
              .map(
                  i -> {
                    Product.ProductImage img = new Product.ProductImage();
                    img.setUrl(i.url());
                    img.setAlt(i.alt());
                    img.setPrimary(i.isPrimary());
                    return img;
                  })
              .toList());
    }
    if (request.price() != null) {
      Product.Price price = new Product.Price();
      price.setAmountCents(request.price().amountCents());
      price.setCurrency(request.price().currency());
      price.setTaxIncluded(request.price().taxIncluded());
      product.setPrice(price);
    }
    if (request.specs() != null) {
      product.setSpecs(
          request.specs().stream()
              .filter(java.util.Objects::nonNull)
              .map(
                  s -> {
                    Product.Spec spec = new Product.Spec();
                    spec.setLabel(s.label());
                    spec.setValue(s.value());
                    return spec;
                  })
              .toList());
    }
    product.setMaxQuantityPerOrder(
        request.maxQuantityPerOrder() != null ? request.maxQuantityPerOrder() : 0);
    product.setMinimumStock(request.minimumStock() != null ? request.minimumStock() : 0);
    product.setActive(true);
    product.setCreatedAt(Instant.now());
    try {
      Product saved = productRepository.save(product);
      return ProductResponse.from(saved, 0, 0);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }
  }

  public ProductResponse updateProduct(String id, UpdateProductRequest request) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    if (request.name() != null) product.setName(request.name());
    if (request.description() != null) product.setDescription(request.description());
    if (request.category() != null) {
      validateCategory(request.category());
      product.setCategory(request.category());
    }
    if (request.images() != null) {
      product.setImages(
          request.images().stream()
              .filter(java.util.Objects::nonNull)
              .map(
                  i -> {
                    Product.ProductImage img = new Product.ProductImage();
                    img.setUrl(i.url());
                    img.setAlt(i.alt());
                    img.setPrimary(i.isPrimary());
                    return img;
                  })
              .toList());
    }
    if (request.price() != null) {
      Product.Price price = new Product.Price();
      price.setAmountCents(request.price().amountCents());
      price.setCurrency(request.price().currency());
      price.setTaxIncluded(request.price().taxIncluded());
      product.setPrice(price);
    }
    if (request.specs() != null) {
      product.setSpecs(
          request.specs().stream()
              .filter(java.util.Objects::nonNull)
              .map(
                  s -> {
                    Product.Spec spec = new Product.Spec();
                    spec.setLabel(s.label());
                    spec.setValue(s.value());
                    return spec;
                  })
              .toList());
    }
    if (request.maxQuantityPerOrder() != null)
      product.setMaxQuantityPerOrder(request.maxQuantityPerOrder());
    if (request.minimumStock() != null) product.setMinimumStock(request.minimumStock());
    if (request.isActive() != null) product.setActive(request.isActive());
    Product saved = productRepository.save(product);
    return ProductResponse.from(saved, computeAvailableStock(id), computeReservedStock(id));
  }

  @Transactional
  public void deleteProduct(String id) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    product.setActive(false);
    productRepository.save(product);
    // Clear all position assignments for this product (cascade effect)
    positionRepository
        .findByProductIdIn(List.of(id))
        .forEach(
            p -> {
              p.setProductId(null);
              p.setCurrentStock(0);
              positionRepository.save(p);
            });
  }

  public List<ProductLocationEntry> getProductLocation(String id) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    List<Position> positions = positionRepository.findByProductIdInAndIsActiveTrue(List.of(id));

    // Bulk-fetch lines and zones — 2 queries instead of 2 × N.
    List<String> lineIds = positions.stream().map(Position::getIdLine).distinct().toList();
    List<String> zoneIds = positions.stream().map(Position::getIdZone).distinct().toList();
    Map<String, com.usal.whbackend.domain.Line> lineMap =
        lineRepository.findAllById(lineIds).stream()
            .collect(Collectors.toMap(com.usal.whbackend.domain.Line::getId, Function.identity()));
    Map<String, com.usal.whbackend.domain.Zone> zoneMap =
        zoneRepository.findAllById(zoneIds).stream()
            .collect(Collectors.toMap(com.usal.whbackend.domain.Zone::getId, Function.identity()));

    return positions.stream()
        .map(
            p -> {
              var line = lineMap.get(p.getIdLine());
              var zone = zoneMap.get(p.getIdZone());
              return new ProductLocationEntry(
                  p.getId(),
                  p.getPositionName(),
                  p.getCurrentStock(),
                  p.getIdLine(),
                  line != null ? line.getNumberLine() : 0,
                  p.getIdZone(),
                  zone != null ? zone.getZoneCode() : null);
            })
        .toList();
  }

  // ── Inner helpers ──────────────────────────────────────────────────────────

  private record StockSum(int total) {}

  private record ProductStockSum(String id, int total) {}

  public record ProductLocationEntry(
      String idPosition,
      String positionName,
      int currentStock,
      String idLine,
      int numberLine,
      String idZone,
      String zoneCode) {}
}
