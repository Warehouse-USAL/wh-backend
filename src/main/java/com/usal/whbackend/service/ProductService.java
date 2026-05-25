package com.usal.whbackend.service;

import com.usal.whbackend.api.product.CreateProductRequest;
import com.usal.whbackend.api.product.UpdateProductRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProductService {

  private final ProductRepository productRepository;
  private final MongoTemplate mongoTemplate;

  public ProductService(ProductRepository productRepository, MongoTemplate mongoTemplate) {
    this.productRepository = productRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public Page<Product> getProducts(
      String category, String search, Boolean active, Pageable pageable) {
    Query query = new Query();
    query.addCriteria(Criteria.where("active").is(active != null ? active : true));
    if (category != null) {
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
    return new PageImpl<>(items, pageable, total);
  }

  public Product getProduct(String id, Boolean isActive) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    if (!Boolean.FALSE.equals(isActive) && !product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    return product;
  }

  public Product createProduct(CreateProductRequest request) {
    if (request.sku() == null
        || request.sku().isBlank()
        || request.name() == null
        || request.name().isBlank()
        || request.category() == null
        || request.category().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_REQUIRED_FIELDS");
    }

    if (productRepository.findBySku(request.sku()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }

    Product product = new Product();
    product.setSku(request.sku());
    product.setName(request.name());
    product.setDescription(request.description());
    product.setCategory(request.category());
    product.setImageUrl(request.imageUrl());
    product.setAvailableStock(request.availableStock() != null ? request.availableStock() : 0);
    product.setMaxQuantityPerOrder(
        request.maxQuantityPerOrder() != null ? request.maxQuantityPerOrder() : 0);
    product.setMinimumStock(request.minimumStock() != null ? request.minimumStock() : 0);
    product.setZone(request.zone());
    product.setLine(request.line());
    product.setPosition(request.position());
    product.setHeight(request.height());
    product.setActive(true);
    product.setReservedStock(0);
    product.setCreatedAt(Instant.now());

    try {
      return productRepository.save(product);
    } catch (DuplicateKeyException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU_ALREADY_EXISTS");
    }
  }

  public Product updateProduct(String id, UpdateProductRequest request) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    if (!product.isActive()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }

    if (request.name() != null) {
      product.setName(request.name());
    }
    if (request.description() != null) {
      product.setDescription(request.description());
    }
    if (request.category() != null) {
      product.setCategory(request.category());
    }
    if (request.imageUrl() != null) {
      product.setImageUrl(request.imageUrl());
    }
    if (request.availableStock() != null) {
      product.setAvailableStock(request.availableStock());
    }
    if (request.maxQuantityPerOrder() != null) {
      product.setMaxQuantityPerOrder(request.maxQuantityPerOrder());
    }
    if (request.zone() != null) {
      product.setZone(request.zone());
    }
    if (request.line() != null) {
      product.setLine(request.line());
    }
    if (request.position() != null) {
      product.setPosition(request.position());
    }
    if (request.height() != null) {
      product.setHeight(request.height());
    }

    return productRepository.save(product);
  }

  public void deleteProduct(String id) {
    Product product =
        productRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    product.setActive(false);
    productRepository.save(product);
  }
}
