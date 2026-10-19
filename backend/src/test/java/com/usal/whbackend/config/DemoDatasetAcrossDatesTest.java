package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.service.metrics.restock.RestockFormula;
import com.usal.whbackend.service.metrics.restock.RestockInputs;
import com.usal.whbackend.service.metrics.restock.RestockParams;
import com.usal.whbackend.service.metrics.restock.RestockResult;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The seed is anchored to the moment it runs, and seasonality, spikes and weekdays all depend on
 * that date. These invariants must hold whatever day the demo is seeded on — including the edges.
 */
class DemoDatasetAcrossDatesTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "2026-01-01T15:00:00Z", // New Year, summer trough
        "2026-02-14T13:00:00Z",
        "2026-03-31T18:00:00Z",
        "2026-05-12T16:00:00Z", // inside Hot Sale
        "2026-06-21T03:30:00Z", // before opening hours
        "2026-07-31T22:00:00Z",
        "2026-08-15T12:00:00Z",
        "2026-09-30T23:59:00Z",
        "2026-10-05T09:00:00Z",
        "2026-11-27T14:00:00Z", // Black Friday
        "2026-11-30T14:00:00Z", // Cyber Monday
        "2026-12-24T20:00:00Z",
        "2027-03-15T11:00:00Z",
        "2028-02-29T15:00:00Z" // leap day
      })
  void invariantsHoldWhateverDayTheSeedRuns(String when) {
    Instant now = Instant.parse(when);
    DemoData data = new DemoDataset(p -> p, now).build();

    noTimestampInTheFuture(data, now);
    catalogueAndWarehousePredateTheHistory(data);
    stockLedgerBalancesWithinCapacity(data);
    restockMetricShowsEveryCase(data, now);
    ordersArriveToday(data, now);
  }

  private static void noTimestampInTheFuture(DemoData data, Instant now) {
    for (Order o : data.getOrders()) {
      assertThat(o.getCreatedAt()).as("created_at %s", o.getId()).isBefore(now);
      if (o.getStartedAt() != null) {
        assertThat(o.getStartedAt()).as("started_at %s", o.getId()).isBefore(now);
      }
      if (o.getCompletedAt() != null) {
        assertThat(o.getCompletedAt()).as("completed_at %s", o.getId()).isBefore(now);
      }
    }
    data.getReceptions().forEach(r -> assertThat(r.getCreatedAt()).isBefore(now));
    data.getRestockOrders().forEach(r -> assertThat(r.getCreatedAt()).isBefore(now));
  }

  private static void catalogueAndWarehousePredateTheHistory(DemoData data) {
    Instant first =
        data.getOrders().stream().map(Order::getCreatedAt).min(Instant::compareTo).orElseThrow();
    Instant firstStock =
        data.getReceptions().stream()
            .map(Reception::getCreatedAt)
            .min(Instant::compareTo)
            .orElseThrow();
    data.getProducts().forEach(p -> assertThat(p.getCreatedAt()).isBefore(firstStock));
    data.getPositions().forEach(p -> assertThat(p.getCreatedAt()).isBefore(firstStock));
    data.getUsers().forEach(u -> assertThat(u.getCreatedAt()).isBefore(first));
  }

  private static void stockLedgerBalancesWithinCapacity(DemoData data) {
    Map<String, Integer> in = new HashMap<>();
    data.getReceptions()
        .forEach(
            r ->
                r.getAssignments()
                    .forEach(a -> in.merge(r.getProductId(), a.getQuantity(), Integer::sum)));
    Map<String, Integer> out = new HashMap<>();
    data.getOrders().stream()
        .filter(o -> o.getStatus() == OrderStatus.COMPLETED)
        .flatMap(o -> o.getItems().stream())
        .forEach(i -> out.merge(i.getProductId(), i.getQuantity(), Integer::sum));
    Map<String, Integer> physical = physical(data);
    Map<String, Integer> reserved = reserved(data);
    for (Product p : data.getProducts()) {
      int onHand = physical.getOrDefault(p.getId(), 0);
      assertThat(onHand)
          .as("ledger for %s", p.getSku())
          .isEqualTo(in.getOrDefault(p.getId(), 0) - out.getOrDefault(p.getId(), 0));
      assertThat(onHand - reserved.getOrDefault(p.getId(), 0))
          .as("net available for %s", p.getSku())
          .isNotNegative();
    }
    for (Position pos : data.getPositions()) {
      assertThat(pos.getCurrentStock()).isBetween(0, pos.getMaximumCapacity());
    }
  }

  private static void restockMetricShowsEveryCase(DemoData data, Instant now) {
    RestockParams params = DemoDataset.DEMO_RESTOCK_PARAMS;
    Map<String, RestockInputs.Demand> demand =
        RestockInputs.demandByProduct(
            data.getOrders(), now, params.recentDays(), params.longDays());
    Map<String, Integer> onOrder =
        RestockInputs.onOrderByProduct(data.getRestockOrders(), data.getReceptions());
    Map<String, Integer> physical = physical(data);
    Map<String, Integer> reserved = reserved(data);
    List<RestockResult> results =
        data.getProducts().stream()
            .map(
                p -> {
                  RestockInputs.Demand d =
                      demand.getOrDefault(p.getId(), new RestockInputs.Demand(0, 0));
                  return RestockFormula.compute(
                      params,
                      d.longTerm(),
                      d.recent(),
                      physical.getOrDefault(p.getId(), 0) - reserved.getOrDefault(p.getId(), 0),
                      onOrder.getOrDefault(p.getId(), 0));
                })
            .toList();
    assertThat(results).anyMatch(RestockResult::shouldRestock);
    assertThat(results)
        .anyMatch(
            r ->
                !r.shouldRestock()
                    && r.onOrderStock() > 0
                    && r.availableStock() <= r.reorderPoint());
    assertThat(results)
        .anyMatch(r -> r.blendedDemand() > 0 && r.onOrderStock() == 0 && !r.shouldRestock());
  }

  private static void ordersArriveToday(DemoData data, Instant now) {
    LocalDate today = LocalDate.ofInstant(now, DemoHistory.ZONE);
    boolean openingHoursPassed = now.atZone(DemoHistory.ZONE).getHour() >= 10;
    long todays =
        data.getOrders().stream()
            .filter(o -> LocalDate.ofInstant(o.getCreatedAt(), DemoHistory.ZONE).equals(today))
            .count();
    if (openingHoursPassed) {
      assertThat(todays).as("orders today").isPositive();
    }
  }

  private static Map<String, Integer> physical(DemoData data) {
    return data.getPositions().stream()
        .filter(p -> p.getProductId() != null)
        .collect(
            Collectors.groupingBy(
                Position::getProductId, Collectors.summingInt(Position::getCurrentStock)));
  }

  private static Map<String, Integer> reserved(DemoData data) {
    return data.getOrders().stream()
        .filter(
            o -> o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.IN_PROGRESS)
        .flatMap(o -> o.getItems().stream())
        .collect(
            Collectors.groupingBy(
                OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
  }
}
