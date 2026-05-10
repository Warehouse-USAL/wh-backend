package com.usal.whbackend.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderItemTest {

    @Test
    void noArgConstructorAndSetters() {
        OrderItem item = new OrderItem();
        item.setProductId("p-1");
        item.setSku("SKU-001");
        item.setQuantity(5);

        assertEquals("p-1", item.getProductId());
        assertEquals("SKU-001", item.getSku());
        assertEquals(5, item.getQuantity());
    }

    @Test
    void allArgConstructor() {
        OrderItem item = new OrderItem("p-2", "SKU-002", 3);

        assertEquals("p-2", item.getProductId());
        assertEquals("SKU-002", item.getSku());
        assertEquals(3, item.getQuantity());
    }
}
