package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.repository.OrderRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @InjectMocks OrderService orderService;

    @Test
    void getOrders_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> orderService.getOrders(null, null, null, null));
    }

    @Test
    void getOrder_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> orderService.getOrder("id-1"));
    }

    @Test
    void createOrder_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> orderService.createOrder(new CreateOrderRequest(null, null), "user-1"));
    }

    @Test
    void cancelOrder_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> orderService.cancelOrder("id-1", "reason"));
    }
}
