package com.usal.whbackend.service.metrics.conversion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class SuggestionConversionServiceTest {

  private static final ConversionParams PARAMS =
      ConversionParams.validated(
          Instant.parse("2026-03-02T00:00:00Z"),
          Instant.parse("2026-03-04T00:00:00Z"),
          "day",
          "UTC");

  private final MongoTemplate mongo = mock(MongoTemplate.class);
  private final SuggestionConversionService service = new SuggestionConversionService(mongo);

  private static Order order() {
    Order o = new Order();
    o.setStatus(OrderStatus.COMPLETED);
    o.setCreatedAt(Instant.parse("2026-03-02T10:00:00Z"));
    o.setItems(List.of(new OrderItem("p1", "S1", 2, null, true)));
    return o;
  }

  @Test
  void bucketsTheOrdersOfTheRangeAndExcludesCancelledOnesInTheQuery() {
    when(mongo.find(any(Query.class), eq(Order.class))).thenReturn(List.of(order()));

    List<ConversionBucket> rows = service.conversion(PARAMS, List.of(), null);

    assertThat(rows)
        .extracting(ConversionBucket::bucket)
        .containsExactly("2026-03-02", "2026-03-03");
    assertThat(rows.get(0).ordersWithRecommendation()).isEqualTo(1);
    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(q.capture(), eq(Order.class));
    assertThat(q.getValue().getQueryObject()).containsKeys("status", "createdAt");
    assertThat(q.getValue().getQueryObject()).doesNotContainKey("items.productId");
  }

  @Test
  void aProductFilterIsPushedIntoTheQuery() {
    when(mongo.find(any(Query.class), eq(Order.class))).thenReturn(List.of(order()));

    service.conversion(PARAMS, List.of("p1"), null);

    ArgumentCaptor<Query> q = ArgumentCaptor.forClass(Query.class);
    verify(mongo).find(q.capture(), eq(Order.class));
    assertThat(q.getValue().getQueryObject()).containsKey("items.productId");
  }

  @Test
  void aCategoryFilterResolvesToTheProductsOfThatCategory() {
    Product p = new Product();
    p.setId("p1");
    when(mongo.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p));
    when(mongo.find(any(Query.class), eq(Order.class))).thenReturn(List.of(order()));

    List<ConversionBucket> rows = service.conversion(PARAMS, List.of(), "tecnologia");

    assertThat(rows.get(0).lines()).isEqualTo(1);
  }

  @Test
  void aCategoryWithNoProductsAnswersZerosWithoutReadingOrders() {
    when(mongo.find(any(Query.class), eq(Product.class))).thenReturn(List.of());

    List<ConversionBucket> rows = service.conversion(PARAMS, List.of(), "TECNOLOGIA");

    assertThat(rows).hasSize(2).allMatch(b -> b.orders() == 0);
    verify(mongo).find(any(Query.class), eq(Product.class));
    verifyNoMoreInteractions(mongo);
  }

  @Test
  void anUnknownCategoryIsInvalidParams() {
    assertThatThrownBy(() -> service.conversion(PARAMS, List.of(), "nope"))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("category");
  }
}
