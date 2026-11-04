package com.usal.whbackend.api.stock;

import com.usal.whbackend.domain.StockAdjustmentType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Lowers a position's stock by {@code quantity}. {@code type} is {@code MANUAL_DECREASE} (default)
 * or {@code DAMAGE}. {@code quantity} is read as a decimal only so that {@code 2.5} is rejected
 * instead of silently truncated to 2; it must be a whole number of at least 1.
 */
public record CreateStockAdjustmentRequest(
    @NotNull @DecimalMin("1") @Digits(integer = 9, fraction = 0) BigDecimal quantity,
    StockAdjustmentType type,
    @Size(max = 500) String reason) {}
