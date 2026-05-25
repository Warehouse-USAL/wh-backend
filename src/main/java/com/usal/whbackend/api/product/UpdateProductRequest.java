package com.usal.whbackend.api.product;

import com.fasterxml.jackson.annotation.JsonProperty;

public record UpdateProductRequest(
    String name,
    String description,
    String category,
    @JsonProperty("image_url") String imageUrl,
    @JsonProperty("available_stock") Integer availableStock,
    @JsonProperty("max_quantity_per_order") Integer maxQuantityPerOrder,
    String zone,
    String line,
    String position,
    String height) {}
