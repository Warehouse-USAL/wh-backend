package com.usal.whbackend.service;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.repository.OrderRepository;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public List<Order> getOrders(String status, String from, String to, String vehicleId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Order getOrder(String id) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Order createOrder(CreateOrderRequest request, String userId) {
        throw new UnsupportedOperationException("not implemented");
    }

    public Order cancelOrder(String id, String reason) {
        throw new UnsupportedOperationException("not implemented");
    }
}
