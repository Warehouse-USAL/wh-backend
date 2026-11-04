package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record UpdatePositionRequest(
    String positionName,
    Boolean isActive,
    @Min(0) Integer currentStock,
    StockSize sizeStockToSave,
    String productId, // new product to assign (or keep current if null)
    Boolean unassignProduct, // if true, clears productId and currentStock
    String qrCode, // optional — text printed in the position's QR
    @Size(max = 500) String reason) // optional — why the stock changed, kept in the history
{}
