package com.usal.whbackend.domain;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

// Deliberately carries no secondary indexes. The fleet is three rovers (RFC section 1), so any
// query is a three-document scan that beats an index lookup — while this is the most
// write-heavy collection in the system, rewritten on every vehicle.telemetry message. Indexes
// here would add write cost on the hottest path to buy nothing. Revisit if the fleet grows by
// orders of magnitude.
@Document(collection = "vehicles")
public class Vehicle {

  @Id private String id = UUID.randomUUID().toString();
  private String name;
  private VehicleStatus status;
  private double positionX;
  private double positionY;
  private int battery;
  private String currentOrderId;
  private Instant lastSeenAt;

  // Set when this vehicle transitions OFFLINE -> IDLE/BUSY (see VehicleTelemetryConsumer), i.e.
  // when it comes back online. Null until the first such transition is observed. Deliberately not
  // "time since registration": a vehicle that has been offline for a week and just reconnected has
  // zero hours of operation, not a week's worth.
  private Instant operationSince;

  public Vehicle() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public VehicleStatus getStatus() {
    return status;
  }

  public void setStatus(VehicleStatus status) {
    this.status = status;
  }

  public double getPositionX() {
    return positionX;
  }

  public void setPositionX(double positionX) {
    this.positionX = positionX;
  }

  public double getPositionY() {
    return positionY;
  }

  public void setPositionY(double positionY) {
    this.positionY = positionY;
  }

  public int getBattery() {
    return battery;
  }

  public void setBattery(int battery) {
    this.battery = battery;
  }

  public String getCurrentOrderId() {
    return currentOrderId;
  }

  public void setCurrentOrderId(String currentOrderId) {
    this.currentOrderId = currentOrderId;
  }

  public Instant getLastSeenAt() {
    return lastSeenAt;
  }

  public void setLastSeenAt(Instant lastSeenAt) {
    this.lastSeenAt = lastSeenAt;
  }

  public Instant getOperationSince() {
    return operationSince;
  }

  public void setOperationSince(Instant operationSince) {
    this.operationSince = operationSince;
  }
}
