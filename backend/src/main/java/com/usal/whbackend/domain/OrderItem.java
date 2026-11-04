package com.usal.whbackend.domain;

public class OrderItem {

  private String productId;
  private String sku;
  private int quantity;
  private Product.Price unitPrice;
  // True when the client took this line from a suggestion; absent in older documents = false.
  private boolean fromRecommendation;

  public OrderItem() {}

  public OrderItem(String productId, String sku, int quantity) {
    this(productId, sku, quantity, null);
  }

  public OrderItem(String productId, String sku, int quantity, Product.Price unitPrice) {
    this.productId = productId;
    this.sku = sku;
    this.quantity = quantity;
    this.unitPrice = unitPrice == null ? null : new Product.Price(unitPrice);
  }

  public OrderItem(
      String productId,
      String sku,
      int quantity,
      Product.Price unitPrice,
      boolean fromRecommendation) {
    this(productId, sku, quantity, unitPrice);
    this.fromRecommendation = fromRecommendation;
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

  public boolean isFromRecommendation() {
    return fromRecommendation;
  }

  public void setFromRecommendation(boolean fromRecommendation) {
    this.fromRecommendation = fromRecommendation;
  }

  public int getQuantity() {
    return quantity;
  }

  public void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  public Product.Price getUnitPrice() {
    return unitPrice == null ? null : new Product.Price(unitPrice);
  }

  public void setUnitPrice(Product.Price unitPrice) {
    this.unitPrice = unitPrice == null ? null : new Product.Price(unitPrice);
  }
}
