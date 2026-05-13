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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import com.usal.whbackend.service.StockEventPublisher;

@Service
public class OrderService {

  private final OrderRepository orderRepository;
  private final ProductRepository productRepository;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final List<StockEventPublisher> stockEventPublishers;

  public OrderService(
      OrderRepository orderRepository,
      ProductRepository productRepository,
      List<OrderEventPublisher> orderEventPublishers,
      List<StockEventPublisher> stockEventPublishers) {
    this.orderRepository = orderRepository;
    this.productRepository = productRepository;
    this.orderEventPublishers = orderEventPublishers;
    this.stockEventPublishers = stockEventPublishers;
  }

  public List<Order> getOrders(String status, String from, String to, String vehicleId) {
    List<Order> orders;

    if (status != null) {
      try {
        orders = orderRepository.findByStatus(OrderStatus.valueOf(status.toUpperCase()));
      } catch (IllegalArgumentException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
      }
    } else if (vehicleId != null) {
      orders = orderRepository.findByAssignedVehicleId(vehicleId);
    } else {
      orders = orderRepository.findAll();
    }

    if (status != null && vehicleId != null) {
      orders = orders.stream().filter(o -> vehicleId.equals(o.getAssignedVehicleId())).toList();
    }

    if (from != null) {
      try {
        Instant fromInstant = Instant.parse(from);
        orders =
            orders.stream()
                .filter(o -> o.getCreatedAt() != null && !o.getCreatedAt().isBefore(fromInstant))
                .toList();
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }
    if (to != null) {
      try {
        Instant toInstant = Instant.parse(to);
        orders =
            orders.stream()
                .filter(o -> o.getCreatedAt() != null && !o.getCreatedAt().isAfter(toInstant))
                .toList();
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FORMAT");
      }
    }

    return orders;
  }

  public Order getOrder(String id) {
    return orderRepository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
  }

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

    List<OrderItem> items = new ArrayList<>();

    for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {
      if (itemRequest.quantity() <= 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
      }

      Product product =
          productRepository
              .findById(itemRequest.productId())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND"));

      if (!product.isActive()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE");
      }

      if (itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_LIMIT");
      }

      if (product.getAvailableStock() < itemRequest.quantity()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
      }

      productRepository.updateStock(
          product.getId(), -itemRequest.quantity(), itemRequest.quantity());
      int newAvailableStock = product.getAvailableStock() - itemRequest.quantity();
      if (newAvailableStock < product.getMinimumStock()) {
        product.setAvailableStock(newAvailableStock);
        stockEventPublishers.forEach(p -> p.broadcastStockAlert(product));
      }
      items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
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

    if (order.getItems() != null) {
      for (OrderItem item : order.getItems()) {
        productRepository.updateStock(item.getProductId(), item.getQuantity(), -item.getQuantity());
      }
    }

    Order cancelled = orderRepository.cancel(order, reason);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(cancelled));
    return cancelled;
  }
}
