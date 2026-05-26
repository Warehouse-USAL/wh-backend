package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "products")
@CompoundIndex(name = "category_active_idx", def = "{'category': 1, 'active': 1}")
public class Product {

  @Id private String id;

  @Indexed(unique = true)
  private String sku;

  private String name;
  private String description;
  private String category;
  private String imageUrl;
  private int maxQuantityPerOrder;
  private int minimumStock;
  private boolean active;
  private Instant createdAt;

  public Product() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public void setImageUrl(String imageUrl) {
    this.imageUrl = imageUrl;
  }

  public int getMaxQuantityPerOrder() {
    return maxQuantityPerOrder;
  }

  public void setMaxQuantityPerOrder(int maxQuantityPerOrder) {
    this.maxQuantityPerOrder = maxQuantityPerOrder;
  }

  public int getMinimumStock() {
    return minimumStock;
  }

  public void setMinimumStock(int minimumStock) {
    this.minimumStock = minimumStock;
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    this.active = active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
