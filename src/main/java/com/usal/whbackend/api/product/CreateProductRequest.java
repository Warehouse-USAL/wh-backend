package com.usal.whbackend.api.product;

public record CreateProductRequest(
        String sku, String name, String description, String category, String imageUrl) {}
