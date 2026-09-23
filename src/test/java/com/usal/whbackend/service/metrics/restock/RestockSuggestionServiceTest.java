package com.usal.whbackend.service.metrics.restock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.service.ProductService;
import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

@ExtendWith(MockitoExtension.class)
class RestockSuggestionServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
  private static final RestockParams EXAMPLE = new RestockParams(0.3, 7, 60, 2, 5, 7);

  @Mock MongoTemplate mongoTemplate;
  @Mock ProductService productService;
  RestockSuggestionService service;

  @BeforeEach
  void setUp() {
    service =
        new RestockSuggestionService(
            mongoTemplate, productService, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void suggest_buildsTheDocumentExampleFromRawDocuments() {
    Product p1 = product("p1", "SKU-1");
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of(p1));
    // 22 u/day over the 53-day long window, 25 u/day over the 7-day recent window.
    when(mongoTemplate.find(any(Query.class), eq(Order.class)))
        .thenReturn(
            List.of(
                order(OrderStatus.COMPLETED, 30, "p1", 22 * 53),
                order(OrderStatus.PENDING, 2, "p1", 25 * 7)));
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());
    when(productService.netAvailableStock(List.of("p1"))).thenReturn(Map.of("p1", 140));

    List<RestockSuggestion> rows = service.suggest(EXAMPLE, List.of(), null);

    assertThat(rows).hasSize(1);
    RestockSuggestion row = rows.get(0);
    assertThat(row.productId()).isEqualTo("p1");
    assertThat(row.sku()).isEqualTo("SKU-1");
    assertThat(row.result().inventoryPosition()).isEqualTo(140);
    assertThat(row.result().shouldRestock()).isTrue();
    assertThat(row.result().suggestedQuantity()).isEqualTo(179);
  }

  @Test
  void suggest_countsOnlyTheUndeliveredPartOfOpenRestockOrders() {
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(product("p1", "SKU-1")));
    when(mongoTemplate.find(any(Query.class), eq(Order.class))).thenReturn(List.of());
    RestockOrder ro = new RestockOrder();
    ro.setId("ro-1");
    ro.setProductId("p1");
    ro.setQuantityRequested(80);
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of(ro));
    Reception rec = new Reception();
    rec.setRestockOrderId("ro-1");
    rec.setProductId("p1");
    rec.setQuantityReceived(30);
    when(mongoTemplate.find(any(Query.class), eq(Reception.class))).thenReturn(List.of(rec));
    when(productService.netAvailableStock(anyList())).thenReturn(Map.of("p1", 10));

    RestockResult r = service.suggest(EXAMPLE, List.of(), null).get(0).result();

    assertThat(r.onOrderStock()).isEqualTo(50);
    assertThat(r.inventoryPosition()).isEqualTo(60);
  }

  @Test
  void suggest_listsSuggestionsFirstThenLargestQuantity() {
    when(mongoTemplate.find(any(Query.class), eq(Product.class)))
        .thenReturn(List.of(product("healthy", "A"), product("small", "B"), product("big", "C")));
    when(mongoTemplate.find(any(Query.class), eq(Order.class)))
        .thenReturn(
            List.of(
                order(OrderStatus.COMPLETED, 30, "healthy", 53),
                order(OrderStatus.COMPLETED, 30, "small", 53),
                order(OrderStatus.COMPLETED, 30, "big", 530)));
    when(mongoTemplate.find(any(Query.class), eq(RestockOrder.class))).thenReturn(List.of());
    when(productService.netAvailableStock(anyList()))
        .thenReturn(Map.of("healthy", 500, "small", 0, "big", 0));

    List<String> ids =
        service.suggest(EXAMPLE, List.of(), null).stream()
            .map(RestockSuggestion::productId)
            .toList();

    assertThat(ids).containsExactly("big", "small", "healthy");
  }

  @Test
  void suggest_withNoMatchingProductsSkipsEveryOtherRead() {
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of());

    assertThat(service.suggest(EXAMPLE, List.of("nope"), null)).isEmpty();
  }

  @Test
  void suggest_normalisesCategoryToUppercase() {
    when(mongoTemplate.find(any(Query.class), eq(Product.class))).thenReturn(List.of());

    service.suggest(EXAMPLE, List.of(), "tecnologia");

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    verify(mongoTemplate).find(query.capture(), eq(Product.class));
    assertThat(query.getValue().getQueryObject().get("category")).isEqualTo("TECNOLOGIA");
  }

  @Test
  void suggest_rejectsAnUnknownCategory() {
    assertThatThrownBy(() -> service.suggest(EXAMPLE, List.of(), "juguetes"))
        .isInstanceOf(InvalidMetricParamsException.class)
        .hasMessageContaining("category");
  }

  private static Product product(String id, String sku) {
    Product p = new Product();
    p.setId(id);
    p.setSku(sku);
    p.setName("Product " + id);
    p.setActive(true);
    return p;
  }

  private static Order order(OrderStatus status, int daysAgo, String productId, int quantity) {
    Order o = new Order();
    o.setStatus(status);
    o.setCreatedAt(NOW.minus(daysAgo, ChronoUnit.DAYS));
    o.setItems(List.of(new OrderItem(productId, "SKU", quantity)));
    return o;
  }
}
