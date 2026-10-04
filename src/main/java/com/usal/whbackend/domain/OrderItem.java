package com.usal.whbackend.domain;

public class OrderItem {

  private String productId;
  private String sku;
  private int quantity;
  private Product.Price unitPrice;

  public OrderItem() {}

  public OrderItem(String productId, String sku, int quantity) {
    this(productId, sku, quantity, null);
  }

  public OrderItem(String productId, String sku, int quantity, Product.Price unitPrice) {
    this.productId = productId;
    this.sku = sku;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
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

  public Product.Price getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(Product.Price unitPrice) {
    this.unitPrice = unitPrice;
  }
}

