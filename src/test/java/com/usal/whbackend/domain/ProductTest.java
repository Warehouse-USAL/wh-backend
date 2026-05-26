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

  @Test
  void productImage_equalsAndHashCode() {
    Product.ProductImage img1 = new Product.ProductImage();
    img1.setUrl("https://cdn.example.com/1.webp");
    img1.setAlt("Front");
    img1.setPrimary(true);

    Product.ProductImage img2 = new Product.ProductImage();
    img2.setUrl("https://cdn.example.com/1.webp");
    img2.setAlt("Front");
    img2.setPrimary(true);

    Product.ProductImage img3 = new Product.ProductImage();
    img3.setUrl("https://cdn.example.com/2.webp");
    img3.setAlt("Back");
    img3.setPrimary(false);

    assertEquals(img1, img1);
    assertEquals(img1, img2);
    assertEquals(img1.hashCode(), img2.hashCode());
    assertNotEquals(img1, img3);
    assertNotEquals(img1, null);
    assertNotEquals(img1, "not an image");
  }

  @Test
  void price_equalsAndHashCode() {
    Product.Price p1 = new Product.Price();
    p1.setAmountCents(4999900L);
    p1.setCurrency("ARS");
    p1.setTaxIncluded(true);

    Product.Price p2 = new Product.Price();
    p2.setAmountCents(4999900L);
    p2.setCurrency("ARS");
    p2.setTaxIncluded(true);

    Product.Price p3 = new Product.Price();
    p3.setAmountCents(1000L);
    p3.setCurrency("USD");
    p3.setTaxIncluded(false);

    assertEquals(p1, p1);
    assertEquals(p1, p2);
    assertEquals(p1.hashCode(), p2.hashCode());
    assertNotEquals(p1, p3);
    assertNotEquals(p1, null);
    assertNotEquals(p1, "not a price");
  }

  @Test
  void spec_equalsAndHashCode() {
    Product.Spec s1 = new Product.Spec();
    s1.setLabel("Peso");
    s1.setValue("250 g");

    Product.Spec s2 = new Product.Spec();
    s2.setLabel("Peso");
    s2.setValue("250 g");

    Product.Spec s3 = new Product.Spec();
    s3.setLabel("Alto");
    s3.setValue("15 cm");

    assertEquals(s1, s1);
    assertEquals(s1, s2);
    assertEquals(s1.hashCode(), s2.hashCode());
    assertNotEquals(s1, s3);
    assertNotEquals(s1, null);
    assertNotEquals(s1, "not a spec");
  }
}
