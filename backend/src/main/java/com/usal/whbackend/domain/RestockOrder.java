package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A request placed with a supplier to replenish a product. Purely a record of what was asked for —
 * it never touches {@link Position#getCurrentStock()}. Stock only moves when a {@link Reception}
 * referencing this order (or none at all) is registered.
 *
 * <p>#134 adds an optional {@code expectedDeliveryDate} the admin may record at creation. It is
 * informational only: it does not expire the order, does not fire jobs and is not considered in any
 * stock computation — RN-03 already keeps restock orders out of available stock.
 *
 * <p>#137 adds a lightweight lifecycle ({@link RestockOrderStatus}) so orders can be cancelled when
 * they will never be fulfilled. Documents saved before this change read as {@link
 * RestockOrderStatus#PENDING} by default (see {@link #getStatus()}).
 */
@Document(collection = "restock_orders")
public class RestockOrder {

  @Id private String id;

  @Indexed private String productId;

  private int quantityRequested;
  private String supplier;
  private String requestedByUserId;

  @Indexed private Instant createdAt;

  /** Optional date the supplier committed to deliver by — informational only. */
  private Instant expectedDeliveryDate;

  @Indexed private RestockOrderStatus status;

  private Instant cancelledAt;
  private String cancelledByUserId;
  private String cancellationReason;

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

  public Instant getExpectedDeliveryDate() {
    return expectedDeliveryDate;
  }

  public void setExpectedDeliveryDate(Instant expectedDeliveryDate) {
    this.expectedDeliveryDate = expectedDeliveryDate;
  }

  /** Defaults to {@link RestockOrderStatus#PENDING} for documents saved before #137. */
  public RestockOrderStatus getStatus() {
    return status == null ? RestockOrderStatus.PENDING : status;
  }

  public void setStatus(RestockOrderStatus status) {
    this.status = status;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public void setCancelledAt(Instant cancelledAt) {
    this.cancelledAt = cancelledAt;
  }

  public String getCancelledByUserId() {
    return cancelledByUserId;
  }

  public void setCancelledByUserId(String cancelledByUserId) {
    this.cancelledByUserId = cancelledByUserId;
  }

  public String getCancellationReason() {
    return cancellationReason;
  }

  public void setCancellationReason(String cancellationReason) {
    this.cancellationReason = cancellationReason;
  }

  public boolean isCancelled() {
    return getStatus() == RestockOrderStatus.CANCELLED;
  }
}
