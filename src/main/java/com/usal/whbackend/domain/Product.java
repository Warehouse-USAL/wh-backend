package com.usal.whbackend.domain;

import java.time.Instant;
import java.util.List;
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
  private List<ProductImage> images;
  private Price price;
  private List<Spec> specs;
  private int maxQuantityPerOrder;
  private int minimumStock;
  private boolean active;
  private double height;
  private double width;
  private double length;
  private double weight;
  private Instant createdAt;

  public Product() {}

  // ── Core getters/setters ───────────────────────────────────────────────────

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

  public List<ProductImage> getImages() {
    return images;
  }

  public void setImages(List<ProductImage> images) {
    this.images = images;
  }

  public Price getPrice() {
    return price;
  }

  public void setPrice(Price price) {
    this.price = price;
  }

  public List<Spec> getSpecs() {
    return specs;
  }

  public void setSpecs(List<Spec> specs) {
    this.specs = specs;
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

  public double getHeight() {
    return height;
  }

  public void setHeight(double height) {
    this.height = height;
  }

  public double getWidth() {
    return width;
  }

  public void setWidth(double width) {
    this.width = width;
  }

  public double getLength() {
    return length;
  }

  public void setLength(double length) {
    this.length = length;
  }

  public double getWeight() {
    return weight;
  }

  public void setWeight(double weight) {
    this.weight = weight;
  }

  public double getVolume() {
    return height * width * length;
  }

  // ── Embedded value objects ─────────────────────────────────────────────────

  public static class ProductImage {
    private String url;
    private String alt;
    private boolean primary;

    public ProductImage() {}

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }

    public String getAlt() {
      return alt;
    }

    public void setAlt(String alt) {
      this.alt = alt;
    }

    public boolean isPrimary() {
      return primary;
    }

    public void setPrimary(boolean primary) {
      this.primary = primary;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ProductImage that)) return false;
      return primary == that.primary
          && java.util.Objects.equals(url, that.url)
          && java.util.Objects.equals(alt, that.alt);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(url, alt, primary);
    }
  }

  public static class Price {
    private long amountCents;
    private String currency;
    private boolean taxIncluded;

    public Price() {}

    public long getAmountCents() {
      return amountCents;
    }

    public void setAmountCents(long amountCents) {
      this.amountCents = amountCents;
    }

    public String getCurrency() {
      return currency;
    }

    public void setCurrency(String currency) {
      this.currency = currency;
    }

    public boolean isTaxIncluded() {
      return taxIncluded;
    }

    public void setTaxIncluded(boolean taxIncluded) {
      this.taxIncluded = taxIncluded;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Price that)) return false;
      return amountCents == that.amountCents
          && taxIncluded == that.taxIncluded
          && java.util.Objects.equals(currency, that.currency);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(amountCents, currency, taxIncluded);
    }
  }

  public static class Spec {
    private String label;
    private String value;

    public Spec() {}

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public String getValue() {
      return value;
    }

    public void setValue(String value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof Spec that)) return false;
      return java.util.Objects.equals(label, that.label)
          && java.util.Objects.equals(value, that.value);
    }

    @Override
    public int hashCode() {
      return java.util.Objects.hash(label, value);
    }
  }
}
