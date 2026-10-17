package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.VehicleIds;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Applies the order status changes Infra reports ({@code in_progress}, {@code completed}, {@code
 * cancelled}). Infra may repeat itself after a restart, so reporting the status an order already
 * has is a no-op; anything else on a finished order is a conflict.
 */
@Service
public class OrderStatusService {

  private final OrderService orderService;
  private final VehicleUpdateExecutor vehicleUpdateExecutor;
  private final VehicleEventPublisher vehicleEventPublisher;

  public OrderStatusService(
      OrderService orderService,
      VehicleUpdateExecutor vehicleUpdateExecutor,
      VehicleEventPublisher vehicleEventPublisher) {
    this.orderService = orderService;
    this.vehicleUpdateExecutor = vehicleUpdateExecutor;
    this.vehicleEventPublisher = vehicleEventPublisher;
  }

  public Order apply(String orderId, String status, String infraVehicleId, String timestamp) {
    OrderStatus target = parseTarget(status);
    Order order = orderService.getOrder(orderId);

    if (order.getStatus() == target) {
      return order;
    }
    if (order.getStatus() == OrderStatus.COMPLETED || order.getStatus() == OrderStatus.CANCELLED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "ORDER_NOT_MODIFIABLE");
    }

    String vehicleId = backendVehicleId(infraVehicleId);
    if (target == OrderStatus.IN_PROGRESS) {
      if (vehicleId == null || vehicleId.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "VEHICLE_ID_REQUIRED");
      }
      return orderService.assignVehicle(orderId, vehicleId);
    }

    // The vehicle on the order is released too: nothing else would move it out of BUSY.
    String assignedVehicleId =
        order.getAssignedVehicleId() != null ? order.getAssignedVehicleId() : vehicleId;
    Order saved = orderService.changeStatus(orderId, target.name(), parseTimestamp(timestamp));
    releaseVehicle(assignedVehicleId);
    return saved;
  }

  /** The vehicle may take its first order before its first telemetry reading: create it here. */
  private String backendVehicleId(String infraVehicleId) {
    if (infraVehicleId == null || infraVehicleId.isBlank()) {
      return null;
    }
    String vehicleId = VehicleIds.fromInfra(infraVehicleId);
    vehicleUpdateExecutor.ensureExists(vehicleId, infraVehicleId.trim());
    return vehicleId;
  }

  private OrderStatus parseTarget(String status) {
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    OrderStatus target;
    try {
      target = OrderStatus.valueOf(status.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    if (target == OrderStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
    }
    return target;
  }

  /** Falls back to now: a bad timestamp must not block a status change that really happened. */
  private Instant parseTimestamp(String timestamp) {
    if (timestamp == null) {
      return Instant.now();
    }
    try {
      return Instant.parse(timestamp);
    } catch (DateTimeParseException e) {
      return Instant.now();
    }
  }

  /**
   * Frees the vehicle of a finished order — otherwise it is left BUSY forever, since {@link
   * OrderService#assignVehicle} is the only other place that moves a vehicle out of BUSY, and it
   * only runs on the *next* assignment.
   */
  private void releaseVehicle(String vehicleId) {
    if (vehicleId == null) {
      return;
    }
    vehicleUpdateExecutor
        .apply(
            vehicleId,
            previous -> new Update().set("status", VehicleStatus.IDLE).set("currentOrderId", null))
        .ifPresent(result -> vehicleEventPublisher.broadcastVehicleUpdate(result.updated()));
  }
}
