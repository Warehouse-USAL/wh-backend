package com.usal.whbackend.domain;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OrderItemTest {

  @Test
  void noArgConstructorAndSetters() {
    OrderItem item = new OrderItem();
    item.setProductId("p-1");
    item.setSku("SKU-001");
    item.setQuantity(5);
    Product.Price price = new Product.Price();
    price.setAmountCents(1200);
    price.setCurrency("ARS");
    price.setTaxIncluded(true);
    item.setUnitPrice(price);

    assertEquals("p-1", item.getProductId());
    assertEquals("SKU-001", item.getSku());
    assertEquals(5, item.getQuantity());
    assertEquals(price, item.getUnitPrice());
  }

  @Test
  void allArgConstructor() {
    Product.Price price = new Product.Price();
    price.setAmountCents(1500);
    price.setCurrency("USD");
    price.setTaxIncluded(false);
    OrderItem item = new OrderItem("p-2", "SKU-002", 3, price);

    assertEquals("p-2", item.getProductId());
    assertEquals("SKU-002", item.getSku());
    assertEquals(3, item.getQuantity());
    assertEquals(price, item.getUnitPrice());

    OrderItem threeArg = new OrderItem("p-3", "SKU-003", 4);
    assertNull(threeArg.getUnitPrice());
  }
}
