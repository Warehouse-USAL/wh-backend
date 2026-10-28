package com.usal.whbackend.domain;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One manual change to a position's stock: who made it, when, why, the stock before and after, and
 * the orders cancelled because the remaining stock could not cover them. Append-only.
 */
@Document(collection = "stock_adjustments")
@CompoundIndex(name = "position_createdAt_idx", def = "{'positionId': 1, 'createdAt': -1}")
@CompoundIndex(name = "product_createdAt_idx", def = "{'productId': 1, 'createdAt': -1}")
public record StockAdjustment(
    @Id String id,
    String positionId,
    String productId, // nullable — a position may hold stock without an assigned product
    StockAdjustmentType type,
    String reason, // nullable free text
    int quantityBefore,
    int quantityAfter,
    String adjustedBy, // user id from the security context, or "system"
    Instant createdAt,
    List<String> cancelledOrderIds) {

  public StockAdjustment {
    cancelledOrderIds = cancelledOrderIds == null ? List.of() : List.copyOf(cancelledOrderIds);
  }
}
