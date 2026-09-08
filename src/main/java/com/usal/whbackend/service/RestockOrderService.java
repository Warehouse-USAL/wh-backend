package com.usal.whbackend.service;

import com.usal.whbackend.api.restock.order.CreateRestockOrderRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.service.exception.RestockOrderNotFoundException;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RestockOrderService {

  private final RestockOrderRepository restockOrderRepository;
  private final ProductRepository productRepository;
  private final MongoTemplate mongoTemplate;

  public RestockOrderService(
      RestockOrderRepository restockOrderRepository,
      ProductRepository productRepository,
      MongoTemplate mongoTemplate) {
    this.restockOrderRepository = restockOrderRepository;
    this.productRepository = productRepository;
    this.mongoTemplate = mongoTemplate;
  }

  public RestockOrder createRestockOrder(CreateRestockOrderRequest request, String userId) {
    Product product =
        productRepository
            .findById(request.productId())
            .filter(Product::isActive)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    RestockOrder order = new RestockOrder();
    order.setProductId(product.getId());
    order.setQuantityRequested(request.quantityRequested());
    order.setSupplier(request.supplier());
    order.setRequestedByUserId(userId);
    order.setCreatedAt(Instant.now());
    return restockOrderRepository.save(order);
  }

  public Page<RestockOrder> getRestockOrders(
      String productId, String supplier, String from, String to, Pageable pageable) {
    Instant fromInstant = parseInstant(from, "INVALID_DATE_FORMAT");
    Instant toInstant = parseInstant(to, "INVALID_DATE_FORMAT");

    Query query = new Query();
    if (productId != null) {
      query.addCriteria(Criteria.where("productId").is(productId));
    }
    if (supplier != null) {
      query.addCriteria(Criteria.where("supplier").is(supplier));
    }
    if (fromInstant != null || toInstant != null) {
      Criteria createdAt = Criteria.where("createdAt");
      if (fromInstant != null) createdAt = createdAt.gte(fromInstant);
      if (toInstant != null) createdAt = createdAt.lte(toInstant);
      query.addCriteria(createdAt);
    }
    long total = mongoTemplate.count(query, RestockOrder.class);
    var items = mongoTemplate.find(query.with(pageable), RestockOrder.class);
    return new PageImpl<>(items, pageable, total);
  }

  public RestockOrder getRestockOrder(String id) {
    return restockOrderRepository
        .findById(id)
        .orElseThrow(() -> new RestockOrderNotFoundException(id));
  }

  /** Sum of {@code Reception.quantityReceived} over every reception linking back to this order. */
  public int computeReceivedSoFar(String restockOrderId) {
    var agg =
        Aggregation.newAggregation(
            Aggregation.match(Criteria.where("restockOrderId").is(restockOrderId)),
            Aggregation.group().sum("quantityReceived").as("total"));
    AggregationResults<QuantitySum> results =
        mongoTemplate.aggregate(agg, "receptions", QuantitySum.class);
    QuantitySum sum = results.getUniqueMappedResult();
    return sum != null ? sum.total() : 0;
  }

  private Instant parseInstant(String value, String errorCode) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, errorCode);
    }
  }

  // Package-private (not private) so the test can construct a non-null aggregation result
  // directly, without a live Mongo aggregation pipeline.
  record QuantitySum(int total) {}
}
