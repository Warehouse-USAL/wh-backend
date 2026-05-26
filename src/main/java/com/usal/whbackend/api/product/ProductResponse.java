package com.usal.whbackend.api.product;

import com.usal.whbackend.domain.Product;
import java.time.Instant;

public record ProductResponse(
    String id,
    String sku,
    String name,
    String description,
    String category,
    String imageUrl,
    Stock stock,
    OrderConstraints orderConstraints,
    boolean active,
    Instant createdAt) {

  public record Stock(int available, int reserved, int min) {}

  public record OrderConstraints(int maxQuantityPerOrder) {}

  public static ProductResponse from(Product product, int availableStock, int reservedStock) {
    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getCategory(),
        product.getImageUrl(),
        new Stock(availableStock, reservedStock, product.getMinimumStock()),
        new OrderConstraints(product.getMaxQuantityPerOrder()),
        product.isActive(),
        product.getCreatedAt());
  }
}
