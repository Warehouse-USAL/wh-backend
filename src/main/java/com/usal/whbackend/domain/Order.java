package com.usal.whbackend.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

// Serves the archetypal dashboard query — "orders with status X over a date range" — and, by the
// prefix rule, status-only filters too. It does NOT serve createdAt-only queries, which is why
// createdAt also carries a standalone index below.
@Document(collection = "orders")
@CompoundIndex(name = "status_createdAt_idx", def = "{'status': 1, 'createdAt': -1}")
public class Order {

  @Id private String id;
  private OrderStatus status;

  // Existing OrderMongoRepository.findByRequestedByUserId scans without this.
  @Indexed private String requestedByUserId;
  private List<OrderItem> items;
  private String destinationArea;

  // Existing OrderMongoRepository.findByAssignedVehicleId scans without this.
  @Indexed private String assignedVehicleId;

  // The default sort for every unsorted /query/orders request, and the field date-range filters
  // use. The compound index above cannot serve it, because its prefix is status.
  @Indexed private Instant createdAt;
  private Instant startedAt;
  private Instant completedAt;
  private String cancelReason;
  private Address address;

  public Order() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public void setStatus(OrderStatus status) {
    this.status = status;
  }

  public String getRequestedByUserId() {
    return requestedByUserId;
  }

  public void setRequestedByUserId(String requestedByUserId) {
    this.requestedByUserId = requestedByUserId;
  }

  public List<OrderItem> getItems() {
    return items == null ? null : new ArrayList<>(items);
  }

  public void setItems(List<OrderItem> items) {
    this.items = items == null ? null : new ArrayList<>(items);
  }

  public String getDestinationArea() {
    return destinationArea;
  }

  public void setDestinationArea(String destinationArea) {
    this.destinationArea = destinationArea;
  }

  public String getAssignedVehicleId() {
    return assignedVehicleId;
  }

  public void setAssignedVehicleId(String assignedVehicleId) {
    this.assignedVehicleId = assignedVehicleId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public void setCancelReason(String cancelReason) {
    this.cancelReason = cancelReason;
  }

  public Address getAddress() {
    return address == null ? null : new Address(address);
  }

  public void setAddress(Address address) {
    this.address = address == null ? null : new Address(address);
  }
}
