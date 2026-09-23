package com.usal.whbackend.service.metrics.restock;

/** One product's restock decision, with the identifiers a table needs to label it. */
public record RestockSuggestion(String productId, String sku, String name, RestockResult result) {}
