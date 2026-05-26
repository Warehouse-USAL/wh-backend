package com.usal.whbackend.api.product;

import com.usal.whbackend.domain.Product;
import java.time.Instant;
import java.util.List;

public record ProductResponse(
    String id,
    String sku,
    String name,
    String description,
    String category,
    List<Image> images,
    Price price,
    List<Spec> specs,
    Stock stock,
    OrderConstraints orderConstraints,
    boolean active,
    Instant createdAt) {

  public record Image(String url, String alt, boolean isPrimary) {}

  public record Price(long amountCents, String currency, boolean taxIncluded) {}

  public record Spec(String label, String value) {}

  public record Stock(int available, int reserved, int min) {}

  public record OrderConstraints(int maxQuantityPerOrder) {}

  public static ProductResponse from(Product product, int availableStock, int reservedStock) {
    List<Image> images =
        product.getImages() == null
            ? List.of()
            : product.getImages().stream()
                .filter(java.util.Objects::nonNull)
                .map(i -> new Image(i.getUrl(), i.getAlt(), i.isPrimary()))
                .toList();

    Price price =
        product.getPrice() == null
            ? null
            : new Price(
                product.getPrice().getAmountCents(),
                product.getPrice().getCurrency(),
                product.getPrice().isTaxIncluded());

    List<Spec> specs =
        product.getSpecs() == null
            ? List.of()
            : product.getSpecs().stream()
                .filter(java.util.Objects::nonNull)
                .map(s -> new Spec(s.getLabel(), s.getValue()))
                .toList();

    return new ProductResponse(
        product.getId(),
        product.getSku(),
        product.getName(),
        product.getDescription(),
        product.getCategory(),
        images,
        price,
        specs,
        new Stock(availableStock, reservedStock, product.getMinimumStock()),
        new OrderConstraints(product.getMaxQuantityPerOrder()),
        product.isActive(),
        product.getCreatedAt());
  }
}
