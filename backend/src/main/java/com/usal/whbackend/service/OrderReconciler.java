package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which orders a product's remaining physical stock can no longer cover. Pure: it reads the
 * orders it is given and touches nothing.
 *
 * <p>In-progress orders are respected: their quantity comes off the stock first and they are never
 * cancelled here, even if the stock is short. Pending orders are then walked from oldest to newest;
 * one that fits entirely is kept (and its quantity reserved), otherwise it is cancelled entirely.
 */
final class OrderReconciler {

  /** Prefix of the cancel reason, which the web uses to show these as stock failures. */
  static final String REASON_PREFIX = "INSUFFICIENT_STOCK: ";

  private OrderReconciler() {}

  /** An order to cancel and the reason to record on it. */
  record Cancellation(Order order, String reason) {}

  /**
   * @param productId the product whose stock went down
   * @param physicalStock units physically on hand across the product's active positions
   * @param activeOrders pending and in-progress orders; those without the product are ignored
   */
  static List<Cancellation> ordersToCancel(
      String productId, int physicalStock, List<Order> activeOrders) {
    int remaining = physicalStock;
    for (Order order : activeOrders) {
      if (order.getStatus() == OrderStatus.IN_PROGRESS) {
        remaining -= quantityOf(order, productId);
      }
    }

    List<Order> pending =
        activeOrders.stream()
            .filter(o -> o.getStatus() == OrderStatus.PENDING)
            .sorted(
                Comparator.comparing(
                        Order::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Order::getId, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

    List<Cancellation> cancellations = new ArrayList<>();
    for (Order order : pending) {
      int needed = quantityOf(order, productId);
      if (needed <= 0) {
        continue;
      }
      if (needed <= remaining) {
        remaining -= needed;
      } else {
        cancellations.add(new Cancellation(order, reason(order, productId, needed, remaining)));
      }
    }
    return cancellations;
  }

  private static int quantityOf(Order order, String productId) {
    List<OrderItem> items = order.getItems();
    if (items == null) {
      return 0;
    }
    return items.stream()
        .filter(i -> productId.equals(i.getProductId()))
        .mapToInt(OrderItem::getQuantity)
        .sum();
  }

  private static String reason(Order order, String productId, int needed, int remaining) {
    String sku =
        order.getItems().stream()
            .filter(i -> productId.equals(i.getProductId()) && i.getSku() != null)
            .map(OrderItem::getSku)
            .findFirst()
            .orElse(productId);
    return REASON_PREFIX
        + "product "
        + sku
        + " needs "
        + needed
        + " units but only "
        + Math.max(0, remaining)
        + " remain available";
  }
}
