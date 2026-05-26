package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ProductTest {

  @Test
  void gettersAndSetters() {
    Product product = new Product();
    Instant now = Instant.now();

    product.setId("id-1");
    product.setSku("SKU-001");
    product.setName("Widget");
    product.setDescription("A widget");
    product.setCategory("electronics");
    product.setImageUrl("http://example.com/img.png");
    product.setMaxQuantityPerOrder(5);
    product.setMinimumStock(10);
    product.setActive(true);
    product.setCreatedAt(now);

    assertEquals("id-1", product.getId());
    assertEquals("SKU-001", product.getSku());
    assertEquals("Widget", product.getName());
    assertEquals("A widget", product.getDescription());
    assertEquals("electronics", product.getCategory());
    assertEquals("http://example.com/img.png", product.getImageUrl());
    assertEquals(5, product.getMaxQuantityPerOrder());
    assertEquals(10, product.getMinimumStock());
    assertTrue(product.isActive());
    assertEquals(now, product.getCreatedAt());
  }
}
