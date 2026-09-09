package com.usal.whbackend.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * What a supplier actually delivered — the remito. Registering one is the only operation that
 * increases {@link Position#getCurrentStock()}: it carries the quantity actually received (which
 * may differ from any linked {@link RestockOrder#getQuantityRequested()}) and how it was split
 * across one or more warehouse positions.
 */
@Document(collection = "receptions")
public class Reception {

  @Id private String id;

  @Indexed private String restockOrderId; // nullable — a reception need not reference an order

  @Indexed private String productId;

  private int quantityReceived;
  private StockSize deliveryUnit;
  private String supplier;
  private List<Assignment> assignments;
  private String receivedByUserId;

  @Indexed private Instant createdAt;

  public Reception() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getRestockOrderId() {
    return restockOrderId;
  }

  public void setRestockOrderId(String restockOrderId) {
    this.restockOrderId = restockOrderId;
  }

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public int getQuantityReceived() {
    return quantityReceived;
  }

  public void setQuantityReceived(int quantityReceived) {
    this.quantityReceived = quantityReceived;
  }

  public StockSize getDeliveryUnit() {
    return deliveryUnit;
  }

  public void setDeliveryUnit(StockSize deliveryUnit) {
    this.deliveryUnit = deliveryUnit;
  }

  public String getSupplier() {
    return supplier;
  }

  public void setSupplier(String supplier) {
    this.supplier = supplier;
  }

  public List<Assignment> getAssignments() {
    return assignments == null ? null : new ArrayList<>(assignments);
  }

  public void setAssignments(List<Assignment> assignments) {
    this.assignments = assignments == null ? null : new ArrayList<>(assignments);
  }

  public String getReceivedByUserId() {
    return receivedByUserId;
  }

  public void setReceivedByUserId(String receivedByUserId) {
    this.receivedByUserId = receivedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /** How much of this reception went into one warehouse position. */
  public static class Assignment {
    private String positionId;
    private int quantity;

    public Assignment() {}

    public Assignment(String positionId, int quantity) {
      this.positionId = positionId;
      this.quantity = quantity;
    }

    public String getPositionId() {
      return positionId;
    }

    public void setPositionId(String positionId) {
      this.positionId = positionId;
    }

    public int getQuantity() {
      return quantity;
    }

    public void setQuantity(int quantity) {
      this.quantity = quantity;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Assignment that)) return false;
      return quantity == that.quantity && java.util.Objects.equals(positionId, that.positionId);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(positionId, quantity);
    }
  }
}
