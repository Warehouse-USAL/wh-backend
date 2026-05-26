package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;

public record UpdatePositionRequest(
    String positionName,
    Boolean isActive,
    @Min(0) Integer currentStock,
    StockSize sizeStockToSave,
    String productId, // new product to assign (or keep current if null)
    Boolean unassignProduct) // if true, clears productId and currentStock
{}
