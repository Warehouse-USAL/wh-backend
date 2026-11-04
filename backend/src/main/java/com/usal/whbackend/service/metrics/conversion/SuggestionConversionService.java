package com.usal.whbackend.service.metrics.conversion;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.service.exception.InvalidMetricParamsException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Serves {@code POST /metrics/suggestion-conversion}: how much of what was ordered came from a
 * recommendation of the suggestions service (the per-line {@code from_recommendation} flag of
 * {@code POST /orders}).
 *
 * <p>Reads only the fields it needs and counts in Java ({@link SuggestionConversion}), like the
 * restock metric: two years of the demo warehouse is a few tens of thousands of small documents.
 */
@Service
public class SuggestionConversionService {

  private final MongoTemplate mongoTemplate;

  public SuggestionConversionService(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * @param productIds restricts to the lines of these products; empty means every product
   * @param category restricts to the lines of one catalogue category, case-insensitive; null means
   *     any. With both, a line must satisfy both.
   */
  public List<ConversionBucket> conversion(
      ConversionParams params, List<String> productIds, String category) {
    Set<String> lineFilter = lineFilter(productIds, category);
    ZoneId zone = params.zone();

    Query query =
        new Query(
            Criteria.where("status")
                .ne(OrderStatus.CANCELLED)
                .and("createdAt")
                .gte(params.from())
                .lt(params.to()));
    if (lineFilter != null) {
      if (lineFilter.isEmpty()) {
        return SuggestionConversion.compute(
            List.of(),
            lineFilter,
            zone,
            params.granularity(),
            firstDay(params, zone),
            lastDay(params, zone));
      }
      query.addCriteria(Criteria.where("items.productId").in(lineFilter));
    }
    query
        .fields()
        .include(
            "status", "createdAt", "items.productId", "items.quantity", "items.fromRecommendation");
    List<Order> orders = mongoTemplate.find(query, Order.class);
    return SuggestionConversion.compute(
        orders,
        lineFilter,
        zone,
        params.granularity(),
        firstDay(params, zone),
        lastDay(params, zone));
  }

  private static LocalDate firstDay(ConversionParams params, ZoneId zone) {
    return params.from().atZone(zone).toLocalDate();
  }

  // {@code to} is exclusive: the last instant counted is a millisecond before it.
  private static LocalDate lastDay(ConversionParams params, ZoneId zone) {
    return params.to().minusMillis(1).atZone(zone).toLocalDate();
  }

  /** The product ids a line must belong to, or null when no filter applies. */
  private Set<String> lineFilter(List<String> productIds, String category) {
    Set<String> ids = productIds == null || productIds.isEmpty() ? null : new HashSet<>(productIds);
    if (category == null || category.isBlank()) {
      return ids;
    }
    String upper = category.trim().toUpperCase(Locale.ROOT);
    if (Arrays.stream(ProductCategory.values()).noneMatch(c -> c.name().equals(upper))) {
      throw new InvalidMetricParamsException(
          "category inválida. Valores aceptados: " + Arrays.toString(ProductCategory.values()));
    }
    Query inCategory = new Query(Criteria.where("category").is(upper));
    inCategory.fields().include("_id");
    Set<String> ofCategory = new HashSet<>();
    mongoTemplate.find(inCategory, Product.class).forEach(p -> ofCategory.add(p.getId()));
    if (ids != null) {
      ofCategory.retainAll(ids);
    }
    return ofCategory;
  }
}
