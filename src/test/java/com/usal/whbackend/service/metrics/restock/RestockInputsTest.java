package com.usal.whbackend.service.metrics.restock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RestockInputsTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

  @Test
  void demand_splitsRecentAndLongWindowsWithoutCountingADayTwice() {
    List<Order> orders =
        List.of(
            order(OrderStatus.PENDING, 1, "p1", 14), // recent: 14 / 7 = 2
            order(OrderStatus.COMPLETED, 10, "p1", 53)); // long: 53 / (60 − 7) = 1

    RestockInputs.Demand d = RestockInputs.demandByProduct(orders, NOW, 7, 60).get("p1");

    assertThat(d.recent()).isCloseTo(2, within(1e-9));
    assertThat(d.longTerm()).isCloseTo(1, within(1e-9));
  }

  @Test
  void demand_orderExactlyOnTheRecentBoundaryCountsOnceAsRecent() {
    List<Order> orders = List.of(order(OrderStatus.COMPLETED, 7, "p1", 7));

    RestockInputs.Demand d = RestockInputs.demandByProduct(orders, NOW, 7, 60).get("p1");

    assertThat(d.recent()).isCloseTo(1, within(1e-9));
    assertThat(d.longTerm()).isZero();
  }

  @Test
  void demand_ignoresCancelledOrdersAndOrdersOlderThanTheLongWindow() {
    List<Order> orders =
        List.of(
            order(OrderStatus.CANCELLED, 2, "p1", 50), order(OrderStatus.COMPLETED, 61, "p1", 50));

    assertThat(RestockInputs.demandByProduct(orders, NOW, 7, 60)).doesNotContainKey("p1");
  }

  @Test
  void demand_sumsEveryItemOfAMultiProductOrder() {
    Order o = order(OrderStatus.IN_PROGRESS, 1, "p1", 7);
    o.setItems(List.of(new OrderItem("p1", "S1", 7), new OrderItem("p2", "S2", 14)));

    Map<String, RestockInputs.Demand> d = RestockInputs.demandByProduct(List.of(o), NOW, 7, 60);

    assertThat(d.get("p1").recent()).isCloseTo(1, within(1e-9));
    assertThat(d.get("p2").recent()).isCloseTo(2, within(1e-9));
  }

  @Test
  void onOrder_isRequestedMinusReceivedPerProduct() {
    List<RestockOrder> restock = List.of(restock("ro-1", "p1", 100), restock("ro-2", "p1", 30));
    List<Reception> receptions = List.of(reception("ro-1", "p1", 60));

    assertThat(RestockInputs.onOrderByProduct(restock, receptions)).containsEntry("p1", 70);
  }

  @Test
  void onOrder_overReceivedOrderClampsToZeroInsteadOfCancellingOthers() {
    List<RestockOrder> restock = List.of(restock("ro-1", "p1", 50), restock("ro-2", "p1", 20));
    List<Reception> receptions = List.of(reception("ro-1", "p1", 80));

    assertThat(RestockInputs.onOrderByProduct(restock, receptions)).containsEntry("p1", 20);
  }

  @Test
  void onOrder_ignoresReceptionsWithoutARestockOrder() {
    List<RestockOrder> restock = List.of(restock("ro-1", "p1", 50));
    List<Reception> receptions = List.of(reception(null, "p1", 50));

    assertThat(RestockInputs.onOrderByProduct(restock, receptions)).containsEntry("p1", 50);
  }

  private static Order order(OrderStatus status, int daysAgo, String productId, int quantity) {
    Order o = new Order();
    o.setStatus(status);
    o.setCreatedAt(NOW.minus(daysAgo, ChronoUnit.DAYS));
    o.setItems(List.of(new OrderItem(productId, "SKU", quantity)));
    return o;
  }

  private static RestockOrder restock(String id, String productId, int requested) {
    RestockOrder r = new RestockOrder();
    r.setId(id);
    r.setProductId(productId);
    r.setQuantityRequested(requested);
    return r;
  }

  private static Reception reception(String restockOrderId, String productId, int received) {
    Reception r = new Reception();
    r.setRestockOrderId(restockOrderId);
    r.setProductId(productId);
    r.setQuantityReceived(received);
    return r;
  }
}
