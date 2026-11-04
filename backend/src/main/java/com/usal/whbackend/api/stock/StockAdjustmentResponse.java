package com.usal.whbackend.api.stock;

import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.domain.StockAdjustmentType;
import java.time.Instant;
import java.util.List;

public record StockAdjustmentResponse(
    String id,
    String positionId,
    String productId,
    StockAdjustmentType type,
    String reason,
    int quantityBefore,
    int quantityAfter,
    String adjustedBy,
    Instant createdAt,
    List<String> cancelledOrderIds) {

  public StockAdjustmentResponse {
    cancelledOrderIds = List.copyOf(cancelledOrderIds);
  }

  public static StockAdjustmentResponse from(StockAdjustment a) {
    return new StockAdjustmentResponse(
        a.id(),
        a.positionId(),
        a.productId(),
        a.type(),
        a.reason(),
        a.quantityBefore(),
        a.quantityAfter(),
        a.adjustedBy(),
        a.createdAt(),
        a.cancelledOrderIds());
  }
}
