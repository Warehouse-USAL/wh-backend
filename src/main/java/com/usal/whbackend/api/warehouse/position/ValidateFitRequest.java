package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.StockSize;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ValidateFitRequest(
    @NotBlank String productId, @Min(1) int quantity, @NotNull StockSize size) {}
