package com.usal.whbackend.service.metrics.restock;

/**
 * The weighted-demand restock formula (RFC_Metricas_Calculadas.md §4.1). Pure: no I/O, so the
 * service and the demo seeder decide with exactly the same arithmetic.
 */
public final class RestockFormula {

  // Absorbs floating-point noise so an exact gap of 100 is not rounded up to 101.
  private static final double EPSILON = 1e-9;

  private RestockFormula() {}

  /**
   * @param available stock free to cover new orders — already net of reservations, which is why
   *     the inventory position does not subtract reserved stock again
   * @param onOrder units requested from suppliers that have not arrived yet
   */
  public static RestockResult compute(
      RestockParams p, double longTermDemand, double recentDemand, int available, int onOrder) {
    double blended = p.alpha() * recentDemand + (1 - p.alpha()) * longTermDemand;
    double safetyStock = longTermDemand * p.safetyDays();
    double reorderPoint = blended * p.leadTimeDays() + safetyStock;
    double targetStock = blended * (p.leadTimeDays() + p.coverageDays()) + safetyStock;

    int position = available + onOrder;
    int gap = (int) Math.ceil(Math.max(0, targetStock - position) - EPSILON);
    // A product with no demand and no stock satisfies 0 <= 0 but has nothing to order.
    boolean shouldRestock = position <= reorderPoint && gap > 0;

    return new RestockResult(
        longTermDemand,
        recentDemand,
        blended,
        safetyStock,
        reorderPoint,
        targetStock,
        available,
        onOrder,
        position,
        shouldRestock,
        shouldRestock ? gap : 0);
  }
}
