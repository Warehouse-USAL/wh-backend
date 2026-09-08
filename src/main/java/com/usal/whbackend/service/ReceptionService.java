package com.usal.whbackend.service;

import com.usal.whbackend.api.restock.reception.CreateReceptionRequest;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ReceptionRepository;
import com.usal.whbackend.repository.RestockOrderRepository;
import com.usal.whbackend.service.exception.ReceptionNotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registers receptions (remitos). {@link #createReception} is the only path that increases
 * warehouse stock — see {@code docs/RFC_Restock_Recepcion.md} RN-04/05/06/07: it is atomic (no
 * draft/confirm split), uses the quantity actually received rather than any requested quantity, and
 * may spread that quantity across several positions.
 */
@Service
public class ReceptionService {

  private final ReceptionRepository receptionRepository;
  private final RestockOrderRepository restockOrderRepository;
  private final ProductRepository productRepository;
  private final PositionService positionService;
  private final MongoTemplate mongoTemplate;

  public ReceptionService(
      ReceptionRepository receptionRepository,
      RestockOrderRepository restockOrderRepository,
      ProductRepository productRepository,
      PositionService positionService,
      MongoTemplate mongoTemplate) {
    this.receptionRepository = receptionRepository;
    this.restockOrderRepository = restockOrderRepository;
    this.productRepository = productRepository;
    this.positionService = positionService;
    this.mongoTemplate = mongoTemplate;
  }

  @Transactional
  public Reception createReception(CreateReceptionRequest request, String userId) {
    Product product =
        productRepository
            .findById(request.productId())
            .filter(Product::isActive)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    int assignedTotal =
        request.assignments().stream()
            .mapToInt(CreateReceptionRequest.AssignmentRequest::quantity)
            .sum();
    if (assignedTotal != request.quantityReceived()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ASSIGNMENT_QUANTITY_MISMATCH");
    }

    if (request.restockOrderId() != null) {
      RestockOrder order =
          restockOrderRepository
              .findById(request.restockOrderId())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(HttpStatus.NOT_FOUND, "RESTOCK_ORDER_NOT_FOUND"));
      if (!order.getProductId().equals(product.getId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RESTOCK_ORDER_PRODUCT_MISMATCH");
      }
    }

    for (CreateReceptionRequest.AssignmentRequest assignment : request.assignments()) {
      positionService.increaseStock(
          assignment.positionId(), product.getId(), assignment.quantity());
    }

    Reception reception = new Reception();
    reception.setRestockOrderId(request.restockOrderId());
    reception.setProductId(product.getId());
    reception.setQuantityReceived(request.quantityReceived());
    reception.setDeliveryUnit(request.deliveryUnit());
    reception.setSupplier(request.supplier());
    reception.setAssignments(
        request.assignments().stream()
            .map(a -> new Reception.Assignment(a.positionId(), a.quantity()))
            .toList());
    reception.setReceivedByUserId(userId);
    reception.setCreatedAt(Instant.now());
    return receptionRepository.save(reception);
  }

  public Page<Reception> getReceptions(
      String productId, String restockOrderId, String from, String to, Pageable pageable) {
    Instant fromInstant = parseInstant(from);
    Instant toInstant = parseInstant(to);

    Query query = new Query();
    if (productId != null) {
      query.addCriteria(Criteria.where("productId").is(productId));
    }
    if (restockOrderId != null) {
      query.addCriteria(Criteria.where("restockOrderId").is(restockOrderId));
    }
    if (fromInstant != null || toInstant != null) {
      Criteria createdAt = Criteria.where("createdAt");
      if (fromInstant != null) createdAt = createdAt.gte(fromInstant);
      if (toInstant != null) createdAt = createdAt.lte(toInstant);
      query.addCriteria(createdAt);
    }
    long total = mongoTemplate.count(query, Reception.class);
    List<Reception> items = mongoTemplate.find(query.with(pageable), Reception.class);
    return new PageImpl<>(items, pageable, total);
  }

  public Reception getReception(String id) {
    return receptionRepository.findById(id).orElseThrow(() -> new ReceptionNotFoundException(id));
  }

  private Instant parseInstant(String value) {
    if (value == null) return null;
    try {
      return Instant.parse(value);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
    }
  }
}
