package com.usal.whbackend.service.metrics.restock;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Turns raw documents into the per-product inputs of {@link RestockFormula}. Pure, so the service
 * and the demo seeder read demand and in-transit stock the same way (RFC_Metricas_Calculadas.md
 * §4.2).
 */
public final class RestockInputs {

  /** Average units per day in each window. */
  public record Demand(double longTerm, double recent) {}

  private RestockInputs() {}

  /**
   * Demand is counted when an order is placed, not when it ships, so orders still pending already
   * count as recent demand. Cancelled orders are not demand. The long window stops where the recent
   * one starts, so no day counts twice — hence dividing by {@code longDays − recentDays}.
   */
  public static Map<String, Demand> demandByProduct(
      List<Order> orders, Instant now, int recentDays, int longDays) {
    Instant recentStart = now.minus(recentDays, ChronoUnit.DAYS);
    Instant longStart = now.minus(longDays, ChronoUnit.DAYS);

    Map<String, int[]> units = new HashMap<>(); // [long, recent]
    for (Order o : orders) {
      if (o.getStatus() == OrderStatus.CANCELLED
          || o.getCreatedAt() == null
          || o.getCreatedAt().isBefore(longStart)
          || o.getItems() == null) {
        continue;
      }
      int slot = o.getCreatedAt().isBefore(recentStart) ? 0 : 1;
      for (OrderItem item : o.getItems()) {
        units.computeIfAbsent(item.getProductId(), k -> new int[2])[slot] += item.getQuantity();
      }
    }

    int longSpan = longDays - recentDays;
    return units.entrySet().stream()
        .collect(
            Collectors.toMap(
                Map.Entry::getKey,
                e ->
                    new Demand(
                        (double) e.getValue()[0] / longSpan,
                        (double) e.getValue()[1] / recentDays)));
  }

  /**
   * Units requested from suppliers that have not arrived yet. Each order is clamped at zero on its
   * own, so an over-delivered order does not cancel out another one still in transit.
   */
  public static Map<String, Integer> onOrderByProduct(
      List<RestockOrder> restockOrders, List<Reception> receptions) {
    Map<String, Integer> receivedByOrder =
        receptions.stream()
            .filter(r -> r.getRestockOrderId() != null)
            .collect(
                Collectors.groupingBy(
                    Reception::getRestockOrderId,
                    Collectors.summingInt(Reception::getQuantityReceived)));

    return restockOrders.stream()
        .filter(ro -> Objects.nonNull(ro.getProductId()))
        .collect(
            Collectors.groupingBy(
                RestockOrder::getProductId,
                Collectors.summingInt(
                    ro ->
                        Math.max(
                            0,
                            ro.getQuantityRequested()
                                - receivedByOrder.getOrDefault(ro.getId(), 0)))));
  }
}
