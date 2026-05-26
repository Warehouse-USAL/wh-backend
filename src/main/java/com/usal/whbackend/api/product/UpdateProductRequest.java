package com.usal.whbackend.api.product;

import jakarta.validation.constraints.Min;

public record UpdateProductRequest(
    String name,
    String description,
    String category,
    String imageUrl,
    @Min(0) Integer maxQuantityPerOrder,
    @Min(0) Integer minimumStock,
    Boolean isActive) {}
