package com.usal.whbackend.domain;

public class OrderItem {

  private String productId;
  private String sku;
  private int quantity;

  public OrderItem() {}

  public OrderItem(String productId, String sku, int quantity) {
    this.productId = productId;
    this.sku = sku;
    this.quantity = quantity;
  }

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public String getSku() {
    return sku;
  }

  public void setSku(String sku) {
    this.sku = sku;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }
}
