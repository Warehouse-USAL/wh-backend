package com.usal.whbackend.domain;

import java.time.Instant;

/**
 * The last restock recommendation computed for a product, embedded in its document. Written only by
 * the daily run ({@code POST /metrics/restock-suggestions/apply}, called by the {@code
 * restock-cron} service) — a snapshot as of {@code calculatedAt}, not a live figure.
 *
 * @param shouldRestock the inventory position had fallen to the reorder point
 * @param suggestedQuantity units to order, 0 when no restock is suggested
 * @param inventoryPosition available stock plus stock on order at {@code calculatedAt}
 */
public record ProductRestock(
    boolean shouldRestock,
    int suggestedQuantity,
    double reorderPoint,
    double targetStock,
    int inventoryPosition,
    Instant calculatedAt) {}
