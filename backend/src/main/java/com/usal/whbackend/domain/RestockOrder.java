package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A request placed with a supplier to replenish a product. Purely a record of what was asked for —
 * it never touches {@link Position#getCurrentStock()}. Stock only moves when a {@link Reception}
 * referencing this order (or none at all) is registered.
 */
@Document(collection = "restock_orders")
public class RestockOrder {

  @Id private String id;

  @Indexed private String productId;

  private int quantityRequested;
  private String supplier;
  private String requestedByUserId;

  @Indexed private Instant createdAt;

  public RestockOrder() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public int getQuantityRequested() {
    return quantityRequested;
  }

  public void setQuantityRequested(int quantityRequested) {
    this.quantityRequested = quantityRequested;
  }

  public String getSupplier() {
    return supplier;
  }

  public void setSupplier(String supplier) {
    this.supplier = supplier;
  }

  public String getRequestedByUserId() {
    return requestedByUserId;
  }

  public void setRequestedByUserId(String requestedByUserId) {
    this.requestedByUserId = requestedByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
