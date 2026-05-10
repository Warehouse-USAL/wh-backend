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
        product.setAvailableStock(100);
        product.setReservedStock(10);
        product.setMaxQuantityPerOrder(5);
        product.setZone("A");
        product.setLine("1");
        product.setPosition("3");
        product.setHeight("1.5m");
        product.setActive(true);
        product.setCreatedAt(now);

        assertEquals("id-1", product.getId());
        assertEquals("SKU-001", product.getSku());
        assertEquals("Widget", product.getName());
        assertEquals("A widget", product.getDescription());
        assertEquals("electronics", product.getCategory());
        assertEquals("http://example.com/img.png", product.getImageUrl());
        assertEquals(100, product.getAvailableStock());
        assertEquals(10, product.getReservedStock());
        assertEquals(5, product.getMaxQuantityPerOrder());
        assertEquals("A", product.getZone());
        assertEquals("1", product.getLine());
        assertEquals("3", product.getPosition());
        assertEquals("1.5m", product.getHeight());
        assertTrue(product.isActive());
        assertEquals(now, product.getCreatedAt());
    }
}
