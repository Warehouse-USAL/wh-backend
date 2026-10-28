package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.service.OrderReconciler.Cancellation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderReconcilerTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private static Order order(
      String id, OrderStatus status, int minutesAfterT0, OrderItem... items) {
    Order o = new Order();
    o.setId(id);
    o.setStatus(status);
    o.setCreatedAt(T0.plusSeconds(minutesAfterT0 * 60L));
    o.setItems(List.of(items));
    return o;
  }

  private static OrderItem item(String productId, int quantity) {
    return new OrderItem(productId, "SKU-" + productId, quantity);
  }

  private static List<String> cancelledIds(List<Cancellation> cancellations) {
    return cancellations.stream().map(c -> c.order().getId()).toList();
  }

  @Test
  void specExample_14Units6Break_inProcess5_pending4Then2() {
    // 14 units, 6 break -> 8 remain. In-process 5 is respected -> 3 left. Oldest pending (4) does
    // not fit and is cancelled; the next (2) fits and is kept.
    List<Order> orders =
        List.of(
            order("in-process", OrderStatus.IN_PROGRESS, 0, item("p", 5)),
            order("pending-4", OrderStatus.PENDING, 1, item("p", 4)),
            order("pending-2", OrderStatus.PENDING, 2, item("p", 2)));

    List<Cancellation> result = OrderReconciler.ordersToCancel("p", 8, orders);

    assertThat(cancelledIds(result)).containsExactly("pending-4");
    assertThat(result.get(0).reason())
        .isEqualTo("INSUFFICIENT_STOCK: product SKU-p needs 4 units but only 3 remain available");
  }

  @Test
  void enoughStock_cancelsNothing() {
    List<Order> orders =
        List.of(
            order("a", OrderStatus.PENDING, 1, item("p", 4)),
            order("b", OrderStatus.IN_PROGRESS, 0, item("p", 6)));

    assertThat(OrderReconciler.ordersToCancel("p", 10, orders)).isEmpty();
  }

  @Test
  void inProcessOrdersAreNeverCancelled_evenWhenStockIsShort() {
    List<Order> orders = List.of(order("in-process", OrderStatus.IN_PROGRESS, 0, item("p", 9)));

    assertThat(OrderReconciler.ordersToCancel("p", 3, orders)).isEmpty();
  }

  @Test
  void inProcessShortfall_leavesNothingForPending() {
    List<Order> orders =
        List.of(
            order("in-process", OrderStatus.IN_PROGRESS, 0, item("p", 9)),
            order("pending", OrderStatus.PENDING, 1, item("p", 1)));

    List<Cancellation> result = OrderReconciler.ordersToCancel("p", 3, orders);

    assertThat(cancelledIds(result)).containsExactly("pending");
    assertThat(result.get(0).reason()).endsWith("but only 0 remain available");
  }

  @Test
  void pendingAreWalkedOldestToNewest_notInListOrder() {
    // Listed newest first; the oldest one must get the stock.
    List<Order> orders =
        List.of(
            order("newest", OrderStatus.PENDING, 30, item("p", 5)),
            order("middle", OrderStatus.PENDING, 20, item("p", 5)),
            order("oldest", OrderStatus.PENDING, 10, item("p", 5)));

    assertThat(cancelledIds(OrderReconciler.ordersToCancel("p", 5, orders)))
        .containsExactlyInAnyOrder("newest", "middle");
  }

  @Test
  void anOrderThatDoesNotFitIsCancelledWhole_andALaterSmallerOneStillFits() {
    List<Order> orders =
        List.of(
            order("big", OrderStatus.PENDING, 1, item("p", 6)),
            order("small", OrderStatus.PENDING, 2, item("p", 5)));

    // 5 remain: the big one cannot be partly served, so it goes; the small one fits entirely.
    assertThat(cancelledIds(OrderReconciler.ordersToCancel("p", 5, orders))).containsExactly("big");
  }

  @Test
  void keptOrdersReserveTheirQuantity() {
    List<Order> orders =
        List.of(
            order("first", OrderStatus.PENDING, 1, item("p", 3)),
            order("second", OrderStatus.PENDING, 2, item("p", 3)));

    assertThat(cancelledIds(OrderReconciler.ordersToCancel("p", 5, orders)))
        .containsExactly("second");
  }

  @Test
  void onlyTheAffectedProductCounts_inAMultiProductOrder() {
    Order multi = order("multi", OrderStatus.PENDING, 1, item("other", 100), item("p", 2));
    Order unrelated = order("unrelated", OrderStatus.PENDING, 2, item("other", 100));

    assertThat(OrderReconciler.ordersToCancel("p", 2, List.of(multi, unrelated))).isEmpty();
    assertThat(cancelledIds(OrderReconciler.ordersToCancel("p", 1, List.of(multi, unrelated))))
        .containsExactly("multi");
  }

  @Test
  void aCancelledMultiProductOrderIsCancelledWholeWithTheShortProductInTheReason() {
    Order multi = order("multi", OrderStatus.PENDING, 1, item("ok", 1), item("p", 4));

    List<Cancellation> result = OrderReconciler.ordersToCancel("p", 3, List.of(multi));

    assertThat(result).hasSize(1);
    assertThat(result.get(0).order().getItems()).hasSize(2);
    assertThat(result.get(0).reason()).contains("SKU-p").doesNotContain("SKU-ok");
  }

  @Test
  void sameCreationTime_tiesBreakById_andMissingTimestampsGoLast() {
    Order b = order("b", OrderStatus.PENDING, 1, item("p", 3));
    Order a = order("a", OrderStatus.PENDING, 1, item("p", 3));
    Order undated = order("undated", OrderStatus.PENDING, 1, item("p", 3));
    undated.setCreatedAt(null);

    assertThat(cancelledIds(OrderReconciler.ordersToCancel("p", 3, List.of(undated, b, a))))
        .containsExactlyInAnyOrder("b", "undated");
  }

  @Test
  void reasonFallsBackToTheProductId_whenTheItemHasNoSku() {
    Order o = order("o", OrderStatus.PENDING, 1, new OrderItem("p", null, 4));

    assertThat(OrderReconciler.ordersToCancel("p", 1, List.of(o)).get(0).reason())
        .contains("product p needs 4");
  }

  @Test
  void ordersWithoutItemsOrOtherStatusesAreIgnored() {
    Order noItems = new Order();
    noItems.setId("none");
    noItems.setStatus(OrderStatus.PENDING);
    Order done = order("done", OrderStatus.COMPLETED, 1, item("p", 50));

    assertThat(OrderReconciler.ordersToCancel("p", 0, List.of(noItems, done))).isEmpty();
  }
}
