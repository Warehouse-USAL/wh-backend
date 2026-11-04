package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.api.stock.CancelledOrderResponse;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.service.PositionService.PositionUpdate;
import java.time.Instant;
import java.util.List;

/**
 * {@link PositionResponse} plus {@code cancelled_orders}: the orders cancelled because the update
 * left too little stock. Every existing field is unchanged, so callers that ignore the extra array
 * keep working.
 */
public record UpdatePositionResponse(
    String idPosition,
    String idLine,
    String idZone,
    String positionName,
    boolean isActive,
    int maximumCapacity,
    StockSize sizeStockToSave,
    String productId,
    String qrCode,
    int currentStock,
    Instant createdAt,
    List<CancelledOrderResponse> cancelledOrders) {

  public UpdatePositionResponse {
    cancelledOrders = List.copyOf(cancelledOrders);
  }

  public static UpdatePositionResponse from(PositionUpdate update) {
    PositionResponse p = PositionResponse.from(update.position());
    return new UpdatePositionResponse(
        p.idPosition(),
        p.idLine(),
        p.idZone(),
        p.positionName(),
        p.isActive(),
        p.maximumCapacity(),
        p.sizeStockToSave(),
        p.productId(),
        p.qrCode(),
        p.currentStock(),
        p.createdAt(),
        update.cancelledOrders().stream().map(CancelledOrderResponse::from).toList());
  }
}
