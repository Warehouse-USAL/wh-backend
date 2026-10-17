package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "positions")
@CompoundIndex(name = "line_name_idx", def = "{'idLine': 1, 'positionName': 1}", unique = true)
public class Position {

  @Id private String id;
  private String idLine;
  private String idZone;
  private String positionName;
  private boolean isActive;
  private int maximumCapacity;
  private StockSize sizeStockToSave;

  @Indexed private String productId; // nullable — source of truth for assignment

  // Text printed in the position's QR code — what a vehicle compares against the stops we send.
  // Sparse so positions without a QR yet do not collide on the unique index.
  @Indexed(unique = true, sparse = true)
  private String qrCode;

  private int currentStock;
  private Instant createdAt;

  public Position() {}

  public String getQrCode() {
    return qrCode;
  }

  public void setQrCode(String qrCode) {
    this.qrCode = qrCode;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getIdLine() {
    return idLine;
  }

  public void setIdLine(String idLine) {
    this.idLine = idLine;
  }

  public String getIdZone() {
    return idZone;
  }

  public void setIdZone(String idZone) {
    this.idZone = idZone;
  }

  public String getPositionName() {
    return positionName;
  }

  public void setPositionName(String positionName) {
    this.positionName = positionName;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    isActive = active;
  }

  public int getMaximumCapacity() {
    return maximumCapacity;
  }

  public void setMaximumCapacity(int maximumCapacity) {
    this.maximumCapacity = maximumCapacity;
  }

  public StockSize getSizeStockToSave() {
    return sizeStockToSave;
  }

  public void setSizeStockToSave(StockSize sizeStockToSave) {
    this.sizeStockToSave = sizeStockToSave;
  }

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public int getCurrentStock() {
    return currentStock;
  }

  public void setCurrentStock(int currentStock) {
    this.currentStock = currentStock;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
