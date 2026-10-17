package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import java.time.Instant;

public record PositionResponse(
    String idPosition,
    String idLine,
    String idZone,
    String positionName,
    boolean isActive,
    int maximumCapacity,
    StockSize sizeStockToSave,
    String productId,
    int currentStock,
    Instant createdAt) {

  public static PositionResponse from(Position p) {
    return new PositionResponse(
        p.getId(),
        p.getIdLine(),
        p.getIdZone(),
        p.getPositionName(),
        p.isActive(),
        p.getMaximumCapacity(),
        p.getSizeStockToSave(),
        p.getProductId(),
        p.getCurrentStock(),
        p.getCreatedAt());
  }
}
