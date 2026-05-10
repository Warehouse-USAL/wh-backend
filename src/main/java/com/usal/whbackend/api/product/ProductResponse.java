package com.usal.whbackend.api.product;

public record ProductResponse(
        String id,
        String sku,
        String name,
        String description,
        String category,
        String imageUrl,
        int availableStock,
        int reservedStock,
        boolean active) {}
