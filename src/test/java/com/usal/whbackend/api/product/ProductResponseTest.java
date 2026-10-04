package com.usal.whbackend.api.product;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.usal.whbackend.domain.Product;
import org.junit.jupiter.api.Test;

class ProductResponseTest {

  @Test
  void from_stockSplitsPhysicalIntoFreeAndReserved_andPhysicalIsTheirSum() {
    Product p = new Product();
    p.setMinimumStock(3);

    // availableStock arg = physical on hand (12); 5 of them reserved by open orders.
    ProductResponse.Stock stock = ProductResponse.from(p, 12, 5).stock();

    assertEquals(7, stock.available());
    assertEquals(5, stock.reserved());
    assertEquals(12, stock.physical());
    assertEquals(3, stock.min());
  }
}
