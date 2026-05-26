package com.usal.whbackend.service;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final ProductRepository productRepository;
  private final ProductService productService;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final List<StockEventPublisher> stockEventPublishers;

  /**
   * Per-product locks that serialize the stock-check → order-save window. Prevents the TOCTOU race
   * where two concurrent POST /orders for the same product both pass the availability check and
   * both get persisted, overselling available stock.
   *
   * <p>NOTE: this is a single-instance solution. Multi-instance deployments require a distributed
   * lock (e.g. Redis Redlock) or an atomic reservation counter in MongoDB.
   */
  private final ConcurrentHashMap<String, ReentrantLock> productLocks = new ConcurrentHashMap<>();

  private ReentrantLock getProductLock(String productId) {
    return productLocks.computeIfAbsent(productId, k -> new ReentrantLock());
  }

  public OrderService(
      OrderRepository orderRepository,
      ProductRepository productRepository,
      ProductService productService,
      List<OrderEventPublisher> orderEventPublishers,
      List<StockEventPublisher> stockEventPublishers) {
    this.orderRepository = orderRepository;
    this.productRepository = productRepository;
    this.productService = productService;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
    this.stockEventPublishers = List.copyOf(stockEventPublishers);
  }

  public Page<Order> getOrders(
      String status, String from, String to, String vehicleId, Pageable pageable) {
    OrderStatus parsedStatus = null;
    if (status != null) {
      try {
        parsedStatus = OrderStatus.valueOf(status.toUpperCase());
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
      }
    }

    Instant fromInstant = null;
    if (from != null) {
      try {
        fromInstant = Instant.parse(from);
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }

    Instant toInstant = null;
    if (to != null) {
      try {
        toInstant = Instant.parse(to);
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }

    return orderRepository.findByFilters(parsedStatus, vehicleId, fromInstant, toInstant, pageable);
  }

  public Order getOrder(String id) {
    return orderRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
  }

  @Transactional
  public Order createOrder(CreateOrderRequest request, String userId) {
    if (request.destinationArea() == null || request.destinationArea().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DESTINATION_AREA_REQUIRED");
    }
    if (request.items() == null || request.items().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED");
    }

    Set<String> seenProductIds = new HashSet<>();
    for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
      if (!seenProductIds.add(itemRequest.productId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DUPLICATE_PRODUCT_IN_ORDER");
      }
    }

    // Pass 1: validate products (existence, active, quantity limits) — no lock held yet.
    List<OrderItem> items = new ArrayList<>();
    for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
      if (itemRequest.quantity() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
      }
      Product product =
          productRepository
              .findById(itemRequest.productId())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
      if (!product.isActive()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE");
      }
      if (product.getMaxQuantityPerOrder() > 0
          && itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_LIMIT");
      }
      items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
    }

    // Pass 2: stock check + save under per-product locks. Locks are acquired in sorted product-ID
    // order to prevent deadlocks when two orders share overlapping product sets.
    List<String> sortedIds = items.stream().map(OrderItem::getProductId).sorted().toList();
    List<ReentrantLock> locks = sortedIds.stream().map(this::getProductLock).toList();
    locks.forEach(ReentrantLock::lock);
    try {
      for (OrderItem item : items) {
        int available = productService.computeAvailableStock(item.getProductId());
        int reserved = productService.computeReservedStock(item.getProductId());
        if (available - reserved < item.getQuantity()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
        }
      }

      Order order = new Order();
      order.setStatus(OrderStatus.PENDING);
      order.setRequestedByUserId(userId);
      order.setItems(items);
      order.setDestinationArea(request.destinationArea());
      order.setCreatedAt(Instant.now());

      Order saved = orderRepository.save(order);
      orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
      return saved;
    } finally {
      locks.forEach(ReentrantLock::unlock);
    }
  }

  public Order cancelOrder(String id, String reason) {
    Order order =
        orderRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
    }

    Order cancelled = orderRepository.cancel(order, reason);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(cancelled));
    return cancelled;
  }
}
