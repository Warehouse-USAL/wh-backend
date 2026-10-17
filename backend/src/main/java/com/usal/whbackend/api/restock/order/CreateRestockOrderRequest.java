package com.usal.whbackend.api.restock.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateRestockOrderRequest(
    @NotBlank String productId, @Min(1) int quantityRequested, @NotBlank String supplier) {}
