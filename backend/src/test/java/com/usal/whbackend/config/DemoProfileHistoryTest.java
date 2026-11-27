package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.service.metrics.restock.RestockFormula;
import com.usal.whbackend.service.metrics.restock.RestockInputs;
import com.usal.whbackend.service.metrics.restock.RestockResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two years of history of the {@code demo} profile, checked at three seeding moments: an ordinary
 * evening, 01:00 on the first day of a month, and the day after Black Friday.
 */
class DemoProfileHistoryTest {

  private static final String EVENING = "2026-11-27T21:00:00Z";
  private static final String FIRST_OF_MONTH_AT_ONE = "2027-03-01T04:00:00Z";
  private static final String AFTER_BLACK_FRIDAY = "2026-11-28T15:00:00Z";

  private static final String CIN = "p-CIN-001";
  private static final String CUR = "p-CUR-001";
  private static final String GOT = "p-GOT-001";

  private static final Map<String, DemoData> CACHE = new ConcurrentHashMap<>();

  private static DemoData at(String now) {
    return CACHE.computeIfAbsent(now, k -> DemoProfileDatasetTest.build(Instant.parse(k)));
  }

  private static LocalDate day(Instant instant) {
    return LocalDate.ofInstant(instant, DemoHistory.ZONE);
  }

  private static List<Order> completed(DemoData data) {
    return data.getOrders().stream().filter(o -> o.getStatus() == OrderStatus.COMPLETED).toList();
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void theHistoryIsAsBigAsTheDashboardWasPromised(String now) {
    assertThat(at(now).getOrders().size()).isBetween(50_000, 65_000);
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void everyDayOfTheTwoYearsHasCompletedAndCancelledOrders(String now) {
    Map<LocalDate, Set<OrderStatus>> byDay =
        at(now).getOrders().stream()
            .collect(
                Collectors.groupingBy(
                    o -> day(o.getCreatedAt()),
                    Collectors.mapping(Order::getStatus, Collectors.toSet())));
    LocalDate today = day(Instant.parse(now));
    for (int d = 1; d <= DemoHistory.HISTORY_DAYS; d++) {
      assertThat(byDay.getOrDefault(today.minusDays(d), Set.of()))
          .as("statuses %d days ago", d)
          .contains(OrderStatus.COMPLETED, OrderStatus.CANCELLED);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void noCustomerOrderIsLeftOpenAndNothingHappensInTheFuture(String now) {
    Instant seededAt = Instant.parse(now);
    DemoData data = at(now);
    assertThat(data.getOrders())
        .extracting(Order::getStatus)
        .containsOnly(OrderStatus.COMPLETED, OrderStatus.CANCELLED);
    for (Order o : data.getOrders()) {
      assertThat(o.getCreatedAt()).isBeforeOrEqualTo(seededAt);
      if (o.getStatus() == OrderStatus.COMPLETED) {
        assertThat(o.getCompletedAt()).isAfter(o.getStartedAt()).isBeforeOrEqualTo(seededAt);
      }
    }
    assertThat(data.getReceptions())
        .allSatisfy(r -> assertThat(r.getCreatedAt()).isBeforeOrEqualTo(seededAt));
    assertThat(data.getRestockOrders())
        .allSatisfy(ro -> assertThat(ro.getCreatedAt()).isBeforeOrEqualTo(seededAt));
    assertThat(data.getOrders()).extracting(Order::getId).doesNotHaveDuplicates();
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void stockoutsAreTheExceptionNotTheRule(String now) {
    DemoData data = at(now);
    long stockouts =
        data.getOrders().stream()
            .filter(o -> "Stock insuficiente".equals(o.getCancelReason()))
            .count();
    assertThat(stockouts).isLessThan(data.getOrders().size() * 8L / 100);
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void todaysStockIsWhatIsPhysicallyOnTheShelves(String now) {
    assertThat(at(now).getPositions())
        .extracting(Position::getPositionName, Position::getCurrentStock)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("PA1", 4),
            org.assertj.core.api.Assertions.tuple("PA2", 9),
            org.assertj.core.api.Assertions.tuple("PA3", 5),
            org.assertj.core.api.Assertions.tuple("PM1", 0),
            org.assertj.core.api.Assertions.tuple("PM2", 0),
            org.assertj.core.api.Assertions.tuple("PM3", 0));
  }

  /** Receptions minus shipped lines is today's stock, and it was never negative on the way. */
  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void theStockLedgerBalancesWithoutEverGoingNegativeOrOverCapacity(String now) {
    DemoData data = at(now);
    record Move(Instant when, int units) {}
    Map<String, List<Move>> moves = new HashMap<>();
    for (Reception r : data.getReceptions()) {
      assertThat(r.getQuantityReceived()).isPositive();
      assertThat(r.getAssignments().stream().mapToInt(Reception.Assignment::getQuantity).sum())
          .isEqualTo(r.getQuantityReceived());
      moves
          .computeIfAbsent(r.getProductId(), k -> new ArrayList<>())
          .add(new Move(r.getCreatedAt(), r.getQuantityReceived()));
    }
    for (Order o : completed(data)) {
      for (OrderItem item : o.getItems()) {
        moves
            .computeIfAbsent(item.getProductId(), k -> new ArrayList<>())
            .add(new Move(o.getCreatedAt(), -item.getQuantity()));
      }
    }
    for (Position pos : data.getPositions()) {
      if (pos.getProductId() == null) {
        continue;
      }
      int balance = 0;
      List<Move> ledger = moves.get(pos.getProductId());
      ledger.sort(Comparator.comparing(Move::when));
      for (Move move : ledger) {
        balance += move.units();
        assertThat(balance)
            .as("%s at %s", pos.getPositionName(), move.when())
            .isBetween(0, pos.getMaximumCapacity());
      }
      assertThat(balance).as(pos.getPositionName()).isEqualTo(pos.getCurrentStock());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void restockOrdersAgreeWithWhatWasReceivedAgainstThem(String now) {
    DemoData data = at(now);
    Map<String, Integer> received =
        data.getReceptions().stream()
            .filter(r -> r.getRestockOrderId() != null)
            .collect(
                Collectors.groupingBy(
                    Reception::getRestockOrderId,
                    Collectors.summingInt(Reception::getQuantityReceived)));
    Set<String> ids =
        data.getRestockOrders().stream().map(RestockOrder::getId).collect(Collectors.toSet());
    assertThat(ids).containsAll(received.keySet());
    for (RestockOrder ro : data.getRestockOrders()) {
      assertThat(ro.getQuantityRequested()).as(ro.getId()).isPositive();
      assertThat(received.getOrDefault(ro.getId(), 0))
          .as(ro.getId())
          .isLessThanOrEqualTo(ro.getQuantityRequested());
    }
    Map<String, Integer> onOrder =
        RestockInputs.onOrderByProduct(data.getRestockOrders(), data.getReceptions());
    assertThat(onOrder.getOrDefault(CIN, 0)).isZero();
    assertThat(onOrder.getOrDefault(GOT, 0)).isZero();
    assertThat(onOrder.getOrDefault(CUR, 0)).isPositive();
  }

  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void plastersAreCoveredByStockOnTheWayAndTheOtherTwoNeedRestocking(String now) {
    DemoData data = at(now);
    var params = DemoDataset.DEMO_RESTOCK_PARAMS;
    var demand =
        RestockInputs.demandByProduct(
            data.getOrders(), Instant.parse(now), params.recentDays(), params.longDays());
    var onOrder = RestockInputs.onOrderByProduct(data.getRestockOrders(), data.getReceptions());
    Map<String, RestockResult> results = new HashMap<>();
    for (Position pos : data.getPositions()) {
      if (pos.getProductId() != null) {
        var d = demand.get(pos.getProductId());
        results.put(
            pos.getProductId(),
            RestockFormula.compute(
                params,
                d.longTerm(),
                d.recent(),
                pos.getCurrentStock(),
                onOrder.getOrDefault(pos.getProductId(), 0)));
      }
    }
    assertThat(results.get(CIN).shouldRestock()).isTrue();
    assertThat(results.get(GOT).shouldRestock()).isTrue();
    assertThat(results.get(CUR).shouldRestock()).isFalse();
    assertThat((double) results.get(CUR).availableStock())
        .isLessThan(results.get(CUR).reorderPoint());
  }

  /** The suggestions service's cart score over the orders it will be fed. */
  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE, AFTER_BLACK_FRIDAY})
  void withPlastersInTheCartGlueIsSuggestedFirstAndCerealBarSecond(String now) {
    Map<String, Integer> count = new HashMap<>();
    Map<String, Integer> pair = new HashMap<>();
    for (Order o : completed(at(now))) {
      List<String> skus = o.getItems().stream().map(OrderItem::getSku).distinct().sorted().toList();
      for (int i = 0; i < skus.size(); i++) {
        count.merge(skus.get(i), 1, Integer::sum);
        for (int j = i + 1; j < skus.size(); j++) {
          pair.merge(skus.get(i) + "|" + skus.get(j), 1, Integer::sum);
        }
      }
    }
    double plastersGlue = cosine(pair, count, "CUR-001", "GOT-001");
    double plastersCereal = cosine(pair, count, "CIN-001", "CUR-001");
    double glueCereal = cosine(pair, count, "CIN-001", "GOT-001");
    assertThat(plastersGlue).isGreaterThanOrEqualTo(1.15 * plastersCereal);
    assertThat(plastersCereal).isGreaterThan(glueCereal);
  }

  private static double cosine(
      Map<String, Integer> pair, Map<String, Integer> count, String a, String b) {
    return pair.get(a + "|" + b) / Math.sqrt((double) count.get(a) * count.get(b));
  }

  @Test
  void someAccountsNeverBuyEveryProductSoTheyCanBeRecommendedOne() {
    Map<String, Set<String>> bought =
        completed(at(EVENING)).stream()
            .collect(
                Collectors.groupingBy(
                    Order::getRequestedByUserId,
                    Collectors.flatMapping(
                        o -> o.getItems().stream().map(OrderItem::getSku), Collectors.toSet())));
    assertThat(bought.get("u-operator-1")).containsExactlyInAnyOrder("CUR-001", "GOT-001");
    assertThat(bought.get("u-operator-2")).containsExactlyInAnyOrder("CUR-001", "CIN-001");
    assertThat(bought.get("u-operator-3")).containsExactlyInAnyOrder("GOT-001");
    assertThat(bought.get("u-sales")).hasSize(3);
  }

  /** Reaching today's stock must not leave a spike or a hole in the last days' charts. */
  @ParameterizedTest
  @ValueSource(strings = {EVENING, FIRST_OF_MONTH_AT_ONE})
  void theLastWeekLooksLikeTheOneBefore(String now) {
    Instant seededAt = Instant.parse(now);
    DemoData data = at(now);
    long last = lines(data, seededAt.minus(7, ChronoUnit.DAYS), seededAt);
    long before =
        lines(data, seededAt.minus(14, ChronoUnit.DAYS), seededAt.minus(7, ChronoUnit.DAYS));
    assertThat((double) last / before).isBetween(0.5, 2.0);
    for (String product : List.of(CIN, CUR, GOT)) {
      assertThat(data.getReceptions())
          .as("a delivery of %s in the last week", product)
          .anyMatch(
              r ->
                  r.getProductId().equals(product)
                      && r.getCreatedAt().isAfter(seededAt.minus(7, ChronoUnit.DAYS)));
    }
  }

  private static long lines(DemoData data, Instant from, Instant to) {
    return completed(data).stream()
        .filter(o -> !o.getCreatedAt().isBefore(from) && o.getCreatedAt().isBefore(to))
        .mapToLong(o -> o.getItems().size())
        .sum();
  }

  @Test
  void suggestionAdoptionAndReceptionsAreSpreadOverBothYears() {
    DemoData data = at(EVENING);
    assertThat(data.getOrders())
        .anyMatch(o -> o.getItems().stream().anyMatch(OrderItem::isFromRecommendation));
    assertThat(
            data.getReceptions().stream()
                .map(r -> day(r.getCreatedAt()).withDayOfMonth(1))
                .distinct()
                .count())
        .isGreaterThanOrEqualTo(24);
    assertThat(data.getOrders())
        .extracting(Order::getAssignedVehicleId)
        .filteredOn(id -> id != null)
        .containsOnly(data.getVehicles().stream().map(v -> v.getId()).toArray(String[]::new));
  }
}
