package com.usal.whbackend.service.metrics.restock;

/** Every intermediate value of the restock formula, so a chart can show why it decided. */
public record RestockResult(
    double longTermDemand,
    double recentDemand,
    double blendedDemand,
    double safetyStock,
    double reorderPoint,
    double targetStock,
    int availableStock,
    int onOrderStock,
    int inventoryPosition,
    boolean shouldRestock,
    int suggestedQuantity) {}
