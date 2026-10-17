package com.usal.whbackend.api.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductRestock;
import java.time.Instant;
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

  @Test
  void from_withoutARestockRun_hasNoRestockRecommendation() {
    assertNull(ProductResponse.from(new Product(), 0, 0).restock());
  }

  @Test
  void from_carriesTheLastRestockRecommendation() {
    Product p = new Product();
    Instant at = Instant.parse("2026-10-05T03:00:00Z");
    p.setRestock(new ProductRestock(true, 179, 158.5, 318.8, 140, at));

    ProductResponse.Restock r = ProductResponse.from(p, 140, 0).restock();

    assertTrue(r.shouldRestock());
    assertEquals(179, r.suggestedQuantity());
    assertEquals(158.5, r.reorderPoint());
    assertEquals(318.8, r.targetStock());
    assertEquals(140, r.inventoryPosition());
    assertEquals(at, r.calculatedAt());
  }
}
