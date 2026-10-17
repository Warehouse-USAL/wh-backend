package com.usal.whbackend.api.metrics;

import com.usal.whbackend.service.metrics.restock.RestockResult;
import com.usal.whbackend.service.metrics.restock.RestockSuggestion;

/** One product in a restock-suggestions response. Decimals are rounded to 2 places for display. */
public record RestockSuggestionRow(
    String productId,
    String sku,
    String name,
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
    int suggestedQuantity) {

  public static RestockSuggestionRow from(RestockSuggestion s) {
    RestockResult r = s.result();
    return new RestockSuggestionRow(
        s.productId(),
        s.sku(),
        s.name(),
        round2(r.longTermDemand()),
        round2(r.recentDemand()),
        round2(r.blendedDemand()),
        round2(r.safetyStock()),
        round2(r.reorderPoint()),
        round2(r.targetStock()),
        r.availableStock(),
        r.onOrderStock(),
        r.inventoryPosition(),
        r.shouldRestock(),
        r.suggestedQuantity());
  }

  private static double round2(double value) {
    return Math.round(value * 100) / 100.0;
  }
}
