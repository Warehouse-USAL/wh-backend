package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductTest {

  @Test
  void gettersAndSetters_coreFields() {
    Product product = new Product();
    Instant now = Instant.now();

    product.setId("id-1");
    product.setSku("SKU-001");
    product.setName("Widget");
    product.setDescription("A widget");
    product.setCategory("electronics");
    product.setMaxQuantityPerOrder(5);
    product.setMinimumStock(10);
    product.setActive(true);
    product.setCreatedAt(now);

    assertEquals("id-1", product.getId());
    assertEquals("SKU-001", product.getSku());
    assertEquals("Widget", product.getName());
    assertEquals("A widget", product.getDescription());
    assertEquals("electronics", product.getCategory());
    assertEquals(5, product.getMaxQuantityPerOrder());
    assertEquals(10, product.getMinimumStock());
    assertTrue(product.isActive());
    assertEquals(now, product.getCreatedAt());
  }

  @Test
  void price_gettersAndSetters() {
    Product product = new Product();
    Product.Price price = new Product.Price();
    price.setAmountCents(4999900L);
    price.setCurrency("ARS");
    price.setTaxIncluded(false);
    product.setPrice(price);

    assertNotNull(product.getPrice());
    assertEquals(4999900L, product.getPrice().getAmountCents());
    assertEquals("ARS", product.getPrice().getCurrency());
    assertFalse(product.getPrice().isTaxIncluded());
  }

  @Test
  void price_defaultsToNull() {
    Product product = new Product();
    assertNull(product.getPrice());
  }

  @Test
  void specs_gettersAndSetters() {
    Product product = new Product();
    Product.Spec spec = new Product.Spec();
    spec.setLabel("Peso");
    spec.setValue("250 g");
    product.setSpecs(List.of(spec));

    assertNotNull(product.getSpecs());
    assertEquals(1, product.getSpecs().size());
    assertEquals("Peso", product.getSpecs().get(0).getLabel());
    assertEquals("250 g", product.getSpecs().get(0).getValue());
  }

  @Test
  void specs_defaultsToNull() {
    Product product = new Product();
    assertNull(product.getSpecs());
  }

  @Test
  void images_gettersAndSetters() {
    Product product = new Product();
    Product.ProductImage img = new Product.ProductImage();
    img.setUrl("https://cdn.example.com/p1/1.webp");
    img.setAlt("Vista frontal");
    img.setPrimary(true);
    product.setImages(List.of(img));

    assertNotNull(product.getImages());
    assertEquals(1, product.getImages().size());
    Product.ProductImage stored = product.getImages().get(0);
    assertEquals("https://cdn.example.com/p1/1.webp", stored.getUrl());
    assertEquals("Vista frontal", stored.getAlt());
    assertTrue(stored.isPrimary());
  }

  @Test
  void images_defaultsToNull() {
    Product product = new Product();
    assertNull(product.getImages());
  }
}
