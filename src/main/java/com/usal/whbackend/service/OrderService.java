package com.usal.whbackend.service;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    public List<Order> getOrders(String status, String from, String to, String vehicleId) {
        List<Order> orders;

        // Filtro principal: status o vehicleId
        if (status != null) {
            orders = orderRepository.findByStatus(OrderStatus.valueOf(status.toUpperCase()));
        } else if (vehicleId != null) {
            orders = orderRepository.findByAssignedVehicleId(vehicleId);
        } else {
            orders = orderRepository.findAll();
        }

        // Si vinieron ambos filtros, aplicamos vehicleId sobre el resultado de status
        if (status != null && vehicleId != null) {
            final String fVehicleId = vehicleId;
            orders = orders.stream()
                    .filter(o -> fVehicleId.equals(o.getAssignedVehicleId()))
                    .toList();
        }

        // Filtro por rango de fechas
        if (from != null) {
            Instant fromInstant = Instant.parse(from);
            orders = orders.stream()
                    .filter(o -> o.getCreatedAt() != null && !o.getCreatedAt().isBefore(fromInstant))
                    .toList();
        }
        if (to != null) {
            Instant toInstant = Instant.parse(to);
            orders = orders.stream()
                    .filter(o -> o.getCreatedAt() != null && !o.getCreatedAt().isAfter(toInstant))
                    .toList();
        }

        return orders;
    }

    public Order getOrder(String id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada"));
    }

    public Order createOrder(CreateOrderRequest request, String userId) {
        List<OrderItem> items = new ArrayList<>();

        // Validar cada producto e item de la orden
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {

            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Producto no encontrado: " + itemRequest.productId()));

            if (!product.isActive()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Producto inactivo: " + product.getSku());
            }

            if (itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Cantidad supera el máximo permitido por orden para: " + product.getSku());
            }

            if (product.getAvailableStock() < itemRequest.quantity()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Stock insuficiente para: " + product.getSku());
            }

            // Reservar stock: descontar de disponible y sumar a reservado
            product.setAvailableStock(product.getAvailableStock() - itemRequest.quantity());
            product.setReservedStock(product.getReservedStock() + itemRequest.quantity());
            productRepository.save(product);

            items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
        }

        // Crear la orden con estado PENDING
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        order.setRequestedByUserId(userId);
        order.setItems(items);
        order.setDestinationArea(request.destinationArea());
        order.setCreatedAt(Instant.now());

        Order saved = orderRepository.save(order);

        // TODO: publicar evento order.dispatch a Redpanda (pendiente coordinación con Grupo 5)

        return saved;
    }

    public Order cancelOrder(String id, String reason) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Orden no encontrada"));

        if (order.getStatus() == OrderStatus.COMPLETED
                || order.getStatus() == OrderStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "La orden no puede cancelarse en su estado actual");
        }

        // Devolver el stock reservado a disponible
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                productRepository.findById(item.getProductId()).ifPresent(product -> {
                    product.setAvailableStock(product.getAvailableStock() + item.getQuantity());
                    product.setReservedStock(
                            Math.max(0, product.getReservedStock() - item.getQuantity()));
                    productRepository.save(product);
                });
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelReason(reason);
        Order saved = orderRepository.save(order);

        // TODO: si la orden estaba IN_PROGRESS, publicar order.cancel a Redpanda

        return saved;
    }
}
