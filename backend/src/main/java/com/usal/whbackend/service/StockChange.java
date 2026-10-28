package com.usal.whbackend.service;

import com.usal.whbackend.domain.StockAdjustmentType;

/**
 * A stock change already applied to a position, handed to {@link StockAdjustmentService} to record
 * and reconcile.
 *
 * @param productId the product the history entry belongs to; null if the position had none
 * @param reconcileProductId the product whose available stock dropped, so its orders must be
 *     re-checked; null when nothing the orders could rely on was lost
 */
public record StockChange(
    String positionId,
    String productId,
    StockAdjustmentType type,
    int quantityBefore,
    int quantityAfter,
    String reconcileProductId,
    String reason) {}
