package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePositionRequest(
    @NotBlank String positionName,
    @Min(1) int maximumCapacity,
    @NotNull StockSize sizeStockToSave) {}
