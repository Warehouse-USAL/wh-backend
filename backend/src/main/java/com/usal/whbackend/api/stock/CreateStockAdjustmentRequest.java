package com.usal.whbackend.api.stock;

import com.usal.whbackend.domain.StockAdjustmentType;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Lowers a position's stock by {@code quantity}. {@code type} is {@code MANUAL_DECREASE} (default)
 * or {@code DAMAGE}.
 */
public record CreateStockAdjustmentRequest(
    @NotNull @Min(1) Integer quantity, StockAdjustmentType type, @Size(max = 500) String reason) {}
