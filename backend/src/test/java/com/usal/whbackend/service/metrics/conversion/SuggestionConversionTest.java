package com.usal.whbackend.service.metrics.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SuggestionConversionTest {

  private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);

  private static Order order(String createdAt, OrderStatus status, OrderItem... items) {
    Order o = new Order();
    o.setStatus(status);
    o.setCreatedAt(Instant.parse(createdAt));
    o.setItems(List.of(items));
    return o;
  }

  private static OrderItem item(String productId, int qty, boolean recommended) {
    return new OrderItem(productId, "SKU-" + productId, qty, null, recommended);
  }

  private static List<ConversionBucket> daily(List<Order> orders, Set<String> products) {
    return SuggestionConversion.compute(
        orders, products, ZoneOffset.UTC, Granularity.DAY, MONDAY, MONDAY.plusDays(2));
  }

  @Test
  void countsOrdersLinesAndUnitsFromARecommendation() {
    List<Order> orders =
        List.of(
            order(
                "2026-03-02T10:00:00Z",
                OrderStatus.COMPLETED,
                item("a", 3, true),
                item("b", 1, false)),
            order("2026-03-02T11:00:00Z", OrderStatus.PENDING, item("a", 1, false)));

    ConversionBucket day = daily(orders, null).get(0);

    assertThat(day.bucket()).isEqualTo("2026-03-02");
    assertThat(day.orders()).isEqualTo(2);
    assertThat(day.ordersWithRecommendation()).isEqualTo(1);
    assertThat(day.orderConversionRate()).isEqualTo(0.5);
    assertThat(day.lines()).isEqualTo(3);
    assertThat(day.linesFromRecommendation()).isEqualTo(1);
    assertThat(day.lineConversionRate()).isEqualTo(0.3333);
    assertThat(day.units()).isEqualTo(5);
    assertThat(day.unitsFromRecommendation()).isEqualTo(3);
    assertThat(day.unitConversionRate()).isEqualTo(0.6);
  }

  @Test
  void cancelledOrdersNeverCount() {
    List<Order> orders =
        List.of(order("2026-03-02T10:00:00Z", OrderStatus.CANCELLED, item("a", 3, true)));

    assertThat(daily(orders, null).get(0).orders()).isZero();
  }

  @Test
  void emptyBucketsAreKeptWithZeroRates() {
    List<ConversionBucket> buckets = daily(List.of(), null);

    assertThat(buckets)
        .extracting(ConversionBucket::bucket)
        .containsExactly("2026-03-02", "2026-03-03", "2026-03-04");
    assertThat(buckets)
        .allSatisfy(
            b -> {
              assertThat(b.orders()).isZero();
              assertThat(b.orderConversionRate()).isZero();
              assertThat(b.unitConversionRate()).isZero();
            });
  }

  @Test
  void aProductFilterLooksAtTheMatchingLinesOnly() {
    List<Order> orders =
        List.of(
            order(
                "2026-03-02T10:00:00Z",
                OrderStatus.COMPLETED,
                item("a", 2, false),
                item("b", 5, true)),
            order("2026-03-02T12:00:00Z", OrderStatus.COMPLETED, item("b", 1, false)));

    ConversionBucket forA = daily(orders, Set.of("a")).get(0);
    assertThat(forA.orders()).isEqualTo(1);
    assertThat(forA.ordersWithRecommendation()).isZero();
    assertThat(forA.units()).isEqualTo(2);

    ConversionBucket forB = daily(orders, Set.of("b")).get(0);
    assertThat(forB.orders()).isEqualTo(2);
    assertThat(forB.ordersWithRecommendation()).isEqualTo(1);
    assertThat(forB.unitConversionRate()).isEqualTo(0.8333);
  }

  @Test
  void aNoMatchFilterGivesZeros() {
    List<Order> orders =
        List.of(order("2026-03-02T10:00:00Z", OrderStatus.COMPLETED, item("a", 2, true)));

    assertThat(daily(orders, Set.of())).allMatch(b -> b.orders() == 0);
  }

  @Test
  void weeksStartOnMondayAndMonthsOnTheFirst() {
    List<Order> orders =
        List.of(
            order("2026-03-08T10:00:00Z", OrderStatus.COMPLETED, item("a", 1, true)),
            order("2026-03-09T10:00:00Z", OrderStatus.COMPLETED, item("a", 1, false)));

    List<ConversionBucket> weeks =
        SuggestionConversion.compute(
            orders,
            null,
            ZoneOffset.UTC,
            Granularity.WEEK,
            LocalDate.of(2026, 3, 4),
            LocalDate.of(2026, 3, 10));
    assertThat(weeks)
        .extracting(ConversionBucket::bucket)
        .containsExactly("2026-03-02", "2026-03-09");
    assertThat(weeks).extracting(ConversionBucket::orders).containsExactly(1, 1);

    List<ConversionBucket> months =
        SuggestionConversion.compute(
            orders,
            null,
            ZoneOffset.UTC,
            Granularity.MONTH,
            LocalDate.of(2026, 2, 20),
            LocalDate.of(2026, 3, 10));
    assertThat(months)
        .extracting(ConversionBucket::bucket)
        .containsExactly("2026-02-01", "2026-03-01");
    assertThat(months).extracting(ConversionBucket::orders).containsExactly(0, 2);
  }

  @Test
  void bucketsAreCutInTheRequestedZone() {
    // 01:00 UTC on the 3rd is still the 2nd in Buenos Aires.
    List<Order> orders =
        List.of(order("2026-03-03T01:00:00Z", OrderStatus.COMPLETED, item("a", 1, true)));

    List<ConversionBucket> buckets =
        SuggestionConversion.compute(
            orders,
            null,
            ZoneId.of("America/Argentina/Buenos_Aires"),
            Granularity.DAY,
            MONDAY,
            MONDAY.plusDays(1));

    assertThat(buckets).extracting(ConversionBucket::orders).containsExactly(1, 0);
  }

  @Test
  void ordersOutsideTheRangeAreIgnored() {
    List<Order> orders =
        List.of(order("2026-04-01T10:00:00Z", OrderStatus.COMPLETED, item("a", 1, true)));

    assertThat(daily(orders, null)).allMatch(b -> b.orders() == 0);
  }
}
