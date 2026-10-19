package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "idempotency_records")
public class IdempotencyRecord {

  @Id private String id;

  @Indexed(unique = true)
  private String key;

  private String userId;
  private String orderId;

  @Indexed(expireAfter = "24h")
  private Instant createdAt;

  public IdempotencyRecord() {}

  public IdempotencyRecord(String key, String userId, String orderId, Instant createdAt) {
    this.key = key;
    this.userId = userId;
    this.orderId = orderId;
    this.createdAt = createdAt;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getUserId() {
    return userId;
  }

  public void setUserId(String userId) {
    this.userId = userId;
  }

  public String getOrderId() {
    return orderId;
  }

  public void setOrderId(String orderId) {
    this.orderId = orderId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
