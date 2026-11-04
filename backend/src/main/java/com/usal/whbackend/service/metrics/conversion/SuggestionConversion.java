package com.usal.whbackend.service.metrics.conversion;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one definition of "conversion": how much of what was ordered came from a suggestion.
 *
 * <p>Pure, so the service and the demo-seed tests share it. Cancelled orders never count (they were
 * not bought, same rule as demand in the restock metric). With a product filter only the matching
 * lines are looked at: an order counts when at least one matching line is in it.
 */
public final class SuggestionConversion {

  private SuggestionConversion() {}

  private static final class Counts {
    int orders;
    int ordersRecommended;
    int lines;
    int linesRecommended;
    long units;
    long unitsRecommended;
  }

  /**
   * @param productIds restricts the lines considered; null means every line
   * @param firstDay first day (inclusive) to report, in {@code zone}
   * @param lastDay last day (inclusive) to report, in {@code zone}
   * @return one bucket per calendar bucket from the one containing {@code firstDay} to the one
   *     containing {@code lastDay}, empty buckets included
   */
  public static List<ConversionBucket> compute(
      List<Order> orders,
      Set<String> productIds,
      ZoneId zone,
      Granularity granularity,
      LocalDate firstDay,
      LocalDate lastDay) {
    Map<LocalDate, Counts> byBucket = new LinkedHashMap<>();
    for (LocalDate b = granularity.start(firstDay); !b.isAfter(lastDay); b = granularity.next(b)) {
      byBucket.put(b, new Counts());
    }
    for (Order order : orders) {
      if (order.getStatus() == OrderStatus.CANCELLED
          || order.getCreatedAt() == null
          || order.getItems() == null) {
        continue;
      }
      Counts counts =
          byBucket.get(granularity.start(order.getCreatedAt().atZone(zone).toLocalDate()));
      if (counts == null) {
        continue;
      }
      boolean any = false;
      boolean anyRecommended = false;
      for (OrderItem item : order.getItems()) {
        if (productIds != null && !productIds.contains(item.getProductId())) {
          continue;
        }
        any = true;
        counts.lines++;
        counts.units += item.getQuantity();
        if (item.isFromRecommendation()) {
          anyRecommended = true;
          counts.linesRecommended++;
          counts.unitsRecommended += item.getQuantity();
        }
      }
      if (any) {
        counts.orders++;
        if (anyRecommended) {
          counts.ordersRecommended++;
        }
      }
    }
    List<ConversionBucket> out = new ArrayList<>(byBucket.size());
    byBucket.forEach(
        (start, c) ->
            out.add(
                new ConversionBucket(
                    start.toString(),
                    c.orders,
                    c.ordersRecommended,
                    rate(c.ordersRecommended, c.orders),
                    c.lines,
                    c.linesRecommended,
                    rate(c.linesRecommended, c.lines),
                    c.units,
                    c.unitsRecommended,
                    rate(c.unitsRecommended, c.units))));
    return out;
  }

  private static double rate(long part, long whole) {
    return whole == 0 ? 0.0 : Math.round(part * 10_000.0 / whole) / 10_000.0;
  }
}
