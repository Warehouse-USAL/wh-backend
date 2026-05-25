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
    Location location,
    boolean active,
    Instant createdAt) {

  public record Stock(int available, int reserved, int minimumStock) {}

  public record OrderConstraints(int maxQuantityPerOrder) {}

  public record Location(String zone, String line, String position, String height) {}

  public static ProductResponse from(Product product) {
    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getCategory(),
        product.getImageUrl(),
        new Stock(
            product.getAvailableStock(), product.getReservedStock(), product.getMinimumStock()),
        new OrderConstraints(product.getMaxQuantityPerOrder()),
        new Location(
            product.getZone(), product.getLine(), product.getPosition(), product.getHeight()),
        product.isActive(),
        product.getCreatedAt());
  }
}
