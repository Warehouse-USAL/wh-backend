package com.usal.whbackend.service.metrics.restock;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.service.ProductService;
import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Serves {@code POST /metrics/restock-suggestions}: reads the raw documents a product's restock
 * decision depends on and applies {@link RestockFormula}.
 *
 * <p>Reads whole documents and aggregates in Java rather than in Mongo pipelines: the volume is
 * tens of products and at most a year of orders, and {@link RestockInputs} is then the one
 * definition of "demand" and "on order" — shared with the demo seeder, which has no database to
 * aggregate in.
 */
@Service
public class RestockSuggestionService {

  private static final Comparator<RestockSuggestion> MOST_URGENT_FIRST =
      Comparator.comparing((RestockSuggestion s) -> !s.result().shouldRestock())
          .thenComparing(s -> -s.result().suggestedQuantity())
          .thenComparing(RestockSuggestion::sku, Comparator.nullsLast(Comparator.naturalOrder()));

  private final MongoTemplate mongoTemplate;
  private final ProductService productService;
  private final Clock clock;

  @Autowired
  public RestockSuggestionService(MongoTemplate mongoTemplate, ProductService productService) {
    this(mongoTemplate, productService, Clock.systemUTC());
  }

  RestockSuggestionService(
      MongoTemplate mongoTemplate, ProductService productService, Clock clock) {
    this.mongoTemplate = mongoTemplate;
    this.productService = productService;
    this.clock = clock;
  }

  /**
   * @param productIds restricts to these products; empty means every active product
   * @param category restricts to one catalogue category, case-insensitive; null means any
   */
  public List<RestockSuggestion> suggest(
      RestockParams params, List<String> productIds, String category) {
    List<Product> products = findProducts(productIds, category);
    if (products.isEmpty()) {
      return List.of();
    }
    List<String> ids = products.stream().map(Product::getId).toList();
    Instant now = clock.instant();

    Map<String, RestockInputs.Demand> demand =
        RestockInputs.demandByProduct(
            findOrders(ids, now.minus(params.longDays(), ChronoUnit.DAYS)),
            now,
            params.recentDays(),
            params.longDays());
    Map<String, Integer> onOrder = findOnOrder(ids);
    Map<String, Integer> available = productService.netAvailableStock(ids);

    RestockInputs.Demand none = new RestockInputs.Demand(0, 0);
    return products.stream()
        .map(
            p -> {
              RestockInputs.Demand d = demand.getOrDefault(p.getId(), none);
              RestockResult result =
                  RestockFormula.compute(
                      params,
                      d.longTerm(),
                      d.recent(),
                      available.getOrDefault(p.getId(), 0),
                      onOrder.getOrDefault(p.getId(), 0));
              return new RestockSuggestion(p.getId(), p.getSku(), p.getName(), result);
            })
        .sorted(MOST_URGENT_FIRST)
        .toList();
  }

  private List<Product> findProducts(List<String> productIds, String category) {
    Query query = new Query(Criteria.where("active").is(true));
    if (productIds != null && !productIds.isEmpty()) {
      query.addCriteria(Criteria.where("_id").in(productIds));
    }
    if (category != null && !category.isBlank()) {
      query.addCriteria(Criteria.where("category").is(normaliseCategory(category)));
    }
    return mongoTemplate.find(query, Product.class);
  }

  private static String normaliseCategory(String category) {
    String upper = category.trim().toUpperCase(Locale.ROOT);
    if (Arrays.stream(ProductCategory.values()).noneMatch(c -> c.name().equals(upper))) {
      throw new InvalidMetricParamsException(
          "category inválida. Valores aceptados: " + Arrays.toString(ProductCategory.values()));
    }
    return upper;
  }

  private List<Order> findOrders(List<String> productIds, Instant since) {
    return mongoTemplate.find(
        new Query(
            Criteria.where("status")
                .ne(OrderStatus.CANCELLED)
                .and("createdAt")
                .gte(since)
                .and("items.productId")
                .in(productIds)),
        Order.class);
  }

  private Map<String, Integer> findOnOrder(List<String> productIds) {
    List<RestockOrder> restockOrders =
        mongoTemplate.find(
            new Query(Criteria.where("productId").in(productIds)), RestockOrder.class);
    if (restockOrders.isEmpty()) {
      return Map.of();
    }
    List<String> restockOrderIds = restockOrders.stream().map(RestockOrder::getId).toList();
    List<Reception> receptions =
        mongoTemplate.find(
            new Query(Criteria.where("restockOrderId").in(restockOrderIds)), Reception.class);
    return RestockInputs.onOrderByProduct(restockOrders, receptions);
  }
}
