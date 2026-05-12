package com.usal.whbackend.api.product;

public record UpdateProductRequest(
        String name,
        String description,
        String category,
        String imageUrl,
        Integer availableStock,
        Integer maxQuantityPerOrder,
        String zone,
        String line,
        String position,
        String height) {}
