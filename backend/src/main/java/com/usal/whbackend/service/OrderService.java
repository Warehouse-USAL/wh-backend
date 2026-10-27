package com.usal.whbackend.service;

import com.mongodb.MongoException;
import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.domain.Address;
import com.usal.whbackend.domain.IdempotencyRecord;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderPriority;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.IdempotencyRecordRepository;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService implements OrderCompletionPort {

  private final OrderRepository orderRepository;
  private final ProductRepository productRepository;
  private final ProductService productService;
  private final VehicleRepository vehicleRepository;
  private final VehicleUpdateExecutor vehicleUpdateExecutor;
  private final StockDrainPort stockDrainPort;
  private final StopResolver stopResolver;
  private final List<OrderEventPublisher> orderEventPublishers;
  private final List<StockEventPublisher> stockEventPublishers;
  private final IdempotencyRecordRepository idempotencyRecordRepository;

  /**
   * Runs order creation in one MongoDB transaction that this service retries itself. Null (plain
   * unit tests, no transaction manager) runs the body directly, without a transaction or retries.
   */
  private TransactionTemplate transactionTemplate;

  /** How many times a creation that lost a write conflict is re-evaluated against fresh data. */
  private int maxCreateAttempts = DEFAULT_CREATE_ATTEMPTS;

  private static final int DEFAULT_CREATE_ATTEMPTS = 40;
  private static final long MAX_BACKOFF_MS = 60;

  @Autowired
  public OrderService(
      OrderRepository orderRepository,
      ProductRepository productRepository,
      ProductService productService,
      VehicleRepository vehicleRepository,
      VehicleUpdateExecutor vehicleUpdateExecutor,
      StockDrainPort stockDrainPort,
      StopResolver stopResolver,
      List<OrderEventPublisher> orderEventPublishers,
      List<StockEventPublisher> stockEventPublishers,
      IdempotencyRecordRepository idempotencyRecordRepository) {
    this.orderRepository = orderRepository;
    this.productRepository = productRepository;
    this.productService = productService;
    this.vehicleRepository = vehicleRepository;
    this.vehicleUpdateExecutor = vehicleUpdateExecutor;
    this.stockDrainPort = stockDrainPort;
    this.stopResolver = stopResolver;
    this.orderEventPublishers = List.copyOf(orderEventPublishers);
    this.stockEventPublishers = List.copyOf(stockEventPublishers);
    this.idempotencyRecordRepository = idempotencyRecordRepository;
  }

  public OrderService(
      OrderRepository orderRepository,
      ProductRepository productRepository,
      ProductService productService,
      VehicleRepository vehicleRepository,
      VehicleUpdateExecutor vehicleUpdateExecutor,
      StockDrainPort stockDrainPort,
      StopResolver stopResolver,
      List<OrderEventPublisher> orderEventPublishers,
      List<StockEventPublisher> stockEventPublishers) {
    this(
        orderRepository,
        productRepository,
        productService,
        vehicleRepository,
        vehicleUpdateExecutor,
        stockDrainPort,
        stopResolver,
        orderEventPublishers,
        stockEventPublishers,
        null);
  }

  @Autowired(required = false)
  public void setTransactionManager(PlatformTransactionManager transactionManager) {
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  /** Overrides how many attempts a conflicting order creation gets (tests, tuning). */
  public void setMaxCreateAttempts(int maxCreateAttempts) {
    this.maxCreateAttempts = Math.max(1, maxCreateAttempts);
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

  public Order createOrder(CreateOrderRequest request, String userId) {
    return createOrder(request, userId, null);
  }

  /**
   * Creates the order in a transaction. Concurrent creations (and stock adjustments) of the same
   * product, and concurrent requests with the same idempotency key, collide on a document written
   * inside the transaction; the loser's transaction aborts with a transient write conflict and is
   * run again from scratch, so it sees what the winner committed: the stock left (the surplus is
   * answered {@code INSUFFICIENT_STOCK}) or the order the key already maps to (replayed).
   */
  public Order createOrder(CreateOrderRequest request, String userId, String idempotencyKey) {
    return inCreationTransaction(
        () -> createOrderInTransaction(request, userId, idempotencyKey),
        idempotencyKey != null && !idempotencyKey.isBlank());
  }

  private Order inCreationTransaction(Supplier<Order> body, boolean keyed) {
    if (transactionTemplate == null
        || TransactionSynchronizationManager.isActualTransactionActive()) {
      // No transaction manager, or the caller's transaction: it owns commit and any retry.
      return body.get();
    }
    RuntimeException last = null;
    for (int attempt = 1; attempt <= maxCreateAttempts; attempt++) {
      try {
        return transactionTemplate.execute(status -> body.get());
      } catch (RuntimeException e) {
        if (!isWriteConflict(e)) {
          throw e;
        }
        last = e;
        backOff(attempt);
      }
    }
    if (last instanceof ResponseStatusException) {
      throw last;
    }
    throw new ResponseStatusException(
        HttpStatus.CONFLICT,
        keyed ? "IDEMPOTENCY_REQUEST_IN_PROGRESS" : "STOCK_CHANGE_CONFLICT",
        last);
  }

  /**
   * Whether {@code error} means "another transaction got there first, run it again": a transient
   * transaction error from MongoDB, a write conflict, or an idempotency claim that lost the race.
   */
  private static boolean isWriteConflict(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t instanceof DuplicateKeyException) {
        return true;
      }
      if (t instanceof MongoException m
          && (m.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL)
              || m.getCode() == WRITE_CONFLICT_CODE)) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static final int WRITE_CONFLICT_CODE = 112;

  private static void backOff(int attempt) {
    long bound = Math.min(MAX_BACKOFF_MS, 4L * attempt) + 1;
    try {
      Thread.sleep(ThreadLocalRandom.current().nextLong(1, bound + 1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(HttpStatus.CONFLICT, "STOCK_CHANGE_CONFLICT", e);
    }
  }

  private Order createOrderInTransaction(
      CreateOrderRequest request, String userId, String idempotencyKey) {
    if (idempotencyKey != null
        && !idempotencyKey.isBlank()
        && idempotencyRecordRepository != null) {
      String trimmedKey = idempotencyKey.trim();
      Optional<IdempotencyRecord> existing = idempotencyRecordRepository.findByKey(trimmedKey);
      if (existing.isPresent()) {
        IdempotencyRecord record = existing.get();
        if (record.getUserId() != null && !record.getUserId().equals(userId)) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_USER_MISMATCH");
        }
        if (record.getOrderId() == null || record.getOrderId().isBlank()) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS");
        }
        return orderRepository
            .findById(record.getOrderId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
      }

      // The unique index is the cross-instance lock. Claim the key before creating an order or
      // publishing its event. The transaction rolls this claim back if order creation fails.
      IdempotencyRecord claim;
      try {
        claim =
            idempotencyRecordRepository.save(
                new IdempotencyRecord(trimmedKey, userId, null, Instant.now()));
      } catch (DuplicateKeyException e) {
        // Another request claimed this key after our read. Do not create an order in this
        // transaction; the retry loop runs it again and then replays the winning order.
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "IDEMPOTENCY_REQUEST_IN_PROGRESS", e);
      }

      Order order = executeCreateOrder(request, userId);
      claim.setOrderId(order.getId());
      idempotencyRecordRepository.save(claim);
      publishOrderUpdateAfterCommit(order);
      return order;
    }

    Order order = executeCreateOrder(request, userId);
    publishOrderUpdateAfterCommit(order);
    return order;
  }

  protected Order executeCreateOrder(CreateOrderRequest request, String userId) {
    if (request.destinationArea() == null || request.destinationArea().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DESTINATION_AREA_REQUIRED");
    }
    if (request.items() == null || request.items().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED");
    }
    if (request.address() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_ADDRESS");
    }
    if (request.address().street() == null || request.address().street().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_ADDRESS_STREET");
    }
    if (request.address().postalCode() == null || request.address().postalCode().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MISSING_ADDRESS_POSTAL_CODE");
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
      Product.Price unitPrice = null;
      if (product.getPrice() != null) {
        unitPrice = new Product.Price();
        unitPrice.setAmountCents(product.getPrice().getAmountCents());
        unitPrice.setCurrency(product.getPrice().getCurrency());
        unitPrice.setTaxIncluded(product.getPrice().isTaxIncluded());
      }
      items.add(
          new OrderItem(product.getId(), product.getSku(), itemRequest.quantity(), unitPrice));
    }

    // Pass 2: stock check + save. Every product bumps its guard document first, in sorted
    // product-ID order: inside a MongoDB transaction reads come from a snapshot and only writes to
    // the same document collide, so two concurrent orders (or an order and a stock adjustment) of
    // one product cannot both commit on the strength of the same availability reading. The loser
    // aborts with a write conflict and createOrder re-runs it against fresh data.
    items.stream()
        .map(OrderItem::getProductId)
        .sorted()
        .forEach(orderRepository::guardStockReconciliation);
    for (OrderItem item : items) {
      int available = productService.computeAvailableStock(item.getProductId());
      int reserved = productService.computeReservedStock(item.getProductId());
      if (available - reserved < item.getQuantity()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
      }
    }

    Address address = new Address();
    address.setStreet(request.address().street());
    address.setDepartment(request.address().department());
    address.setFloor(request.address().floor());
    address.setPostalCode(request.address().postalCode());

    long totalAmountCents = 0L;
    String currency = "ARS";
    boolean taxIncluded = true;
    boolean hasAnyPrice = false;

    for (OrderItem item : items) {
      if (item.getUnitPrice() != null) {
        hasAnyPrice = true;
        totalAmountCents += item.getUnitPrice().getAmountCents() * item.getQuantity();
        if (item.getUnitPrice().getCurrency() != null) {
          currency = item.getUnitPrice().getCurrency();
        }
        taxIncluded = item.getUnitPrice().isTaxIncluded();
      }
    }

    Order order = new Order();
    order.setStatus(OrderStatus.PENDING);
    order.setPriority(request.priority() != null ? request.priority() : OrderPriority.MEDIUM);
    order.setRequestedByUserId(userId);
    order.setItems(items);
    order.setStops(stopResolver.resolve(items));
    order.setDestinationArea(request.destinationArea());
    order.setAddress(address);
    order.setCreatedAt(Instant.now());

    if (hasAnyPrice) {
      Product.Price total = new Product.Price();
      total.setAmountCents(totalAmountCents);
      total.setCurrency(currency);
      total.setTaxIncluded(taxIncluded);
      order.setTotal(total);
    }

    return orderRepository.saveWithoutPublishing(order);
  }

  private void publishOrderUpdateAfterCommit(Order order) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      publishCreatedOrder(order);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            publishCreatedOrder(order);
          }
        });
  }

  private void publishCreatedOrder(Order order) {
    orderRepository.publishPendingOrder(order);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(order));
  }

  /** Administrator/internal overload that may cancel any order. */
  public Order cancelOrder(String id, String reason) {
    return cancelOrder(id, reason, null, true);
  }

  /**
   * Cancels an order or, if a vehicle is already working on it, asks Infra to. A pending order is
   * cancelled at once. An in-progress one stays IN_PROGRESS with {@code cancellationRequestedAt}
   * set until Infra reports it cancelled, since the vehicle must first put the goods back; asking
   * again meanwhile returns it unchanged. Either way Infra is told on the {@code orders} topic.
   *
   * @param requesterId the user asking; must have requested the order unless {@code anyOrder}
   * @param anyOrder whether the requester may cancel orders other users requested
   */
  public Order cancelOrder(String id, String reason, String requesterId, boolean anyOrder) {
    Order order =
        orderRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    if (!anyOrder && (requesterId == null || !requesterId.equals(order.getRequestedByUserId()))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ORDER_NOT_OWNED");
    }
    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
    }
    if (order.getStatus() == OrderStatus.IN_PROGRESS
        && order.getCancellationRequestedAt() != null) {
      return order;
    }

    Order saved =
        order.getStatus() == OrderStatus.IN_PROGRESS
            ? orderRepository.requestCancellation(order, reason)
            : orderRepository.cancel(order, reason);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
    return saved;
  }

  @Transactional
  public Order assignVehicle(String orderId, String vehicleId) {
    Order order =
        orderRepository
            .findById(orderId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_ASSIGNABLE");
    }

    Vehicle vehicle =
        vehicleRepository
            .findById(vehicleId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND"));

    if (vehicle.getStatus() == VehicleStatus.BUSY
        && vehicle.getCurrentOrderId() != null
        && !vehicle.getCurrentOrderId().equals(orderId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "VEHICLE_ALREADY_BUSY");
    }

    String previousVehicleId = order.getAssignedVehicleId();
    if (previousVehicleId != null && !previousVehicleId.equals(vehicleId)) {
      vehicleUpdateExecutor.apply(
          previousVehicleId,
          previous -> new Update().set("currentOrderId", null).set("status", VehicleStatus.IDLE));
    }

    if (order.getStatus() == OrderStatus.PENDING) {
      order.setStartedAt(Instant.now());
    }
    order.setStatus(OrderStatus.IN_PROGRESS);
    order.setAssignedVehicleId(vehicleId);

    vehicleUpdateExecutor.apply(
        vehicleId,
        previous -> new Update().set("currentOrderId", orderId).set("status", VehicleStatus.BUSY));

    Order saved = orderRepository.update(order);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
    return saved;
  }

  /**
   * Forces an order into the given status, simulating the asynchronous transition that would
   * normally be reported by Infra through {@code POST /infra/orders/{id}/status}. Completing an
   * order drains its stock (FIFO) exactly as the consumer does. Reverting to PENDING is rejected,
   * and orders already in a terminal state (COMPLETED/CANCELLED) cannot be modified.
   */
  public Order changeStatus(String id, String status) {
    return changeStatus(id, status, null);
  }

  /**
   * Same as {@link #changeStatus(String, String)}, with an optional {@code completedAt} that is
   * honoured only when completing (defaults to now). Lets the demo seeder back-date completions.
   */
  @Transactional
  public Order changeStatus(String id, String status, Instant completedAt) {
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    OrderStatus target;
    try {
      target = OrderStatus.valueOf(status.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    Order order =
        orderRepository
            .findById(id)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_MODIFIABLE");
    }

    switch (target) {
      case IN_PROGRESS -> {
        if (order.getStartedAt() == null) {
          order.setStartedAt(Instant.now());
        }
        order.setStatus(OrderStatus.IN_PROGRESS);
      }
      case COMPLETED -> completeOrder(order, completedAt != null ? completedAt : Instant.now());
      case CANCELLED -> order.setStatus(OrderStatus.CANCELLED);
      default -> {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION");
      }
    }

    Order saved = orderRepository.update(order);
    orderEventPublishers.forEach(p -> p.broadcastOrderUpdate(saved));
    return saved;
  }

  /**
   * Single path for finishing an order, shared by the Infra status endpoint and {@link
   * #changeStatus}: marks it COMPLETED, drains its stock and publishes {@code order.completed}. The
   * caller persists the order afterwards.
   */
  @Override
  public void completeOrder(Order order, Instant completedAt) {
    order.setStatus(OrderStatus.COMPLETED);
    order.setCompletedAt(completedAt);
    stockDrainPort.drain(order.getItems());
    orderRepository.publishCompleted(order);
  }
}
