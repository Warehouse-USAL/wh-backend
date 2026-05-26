package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;

public record PositionDetailResponse(
    String idPosition,
    String idLine,
    String idZone,
    String positionName,
    boolean isActive,
    int maximumCapacity,
    StockSize sizeStockToSave,
    String productId,
    int currentStock,
    Instant createdAt,
    AssignedProduct assignedProduct) {

  public record AssignedProduct(String id, String sku, String name) {}

  public static PositionDetailResponse from(Position p, Product product) {
    AssignedProduct ap =
        product == null
            ? null
            : new AssignedProduct(product.getId(), product.getSku(), product.getName());
    return new PositionDetailResponse(
        p.getId(),
        p.getIdLine(),
        p.getIdZone(),
        p.getPositionName(),
        p.isActive(),
        p.getMaximumCapacity(),
        p.getSizeStockToSave(),
        p.getProductId(),
        p.getCurrentStock(),
        p.getCreatedAt(),
        ap);
  }
}
