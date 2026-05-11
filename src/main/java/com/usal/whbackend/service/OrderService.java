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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));
    }

    public Order createOrder(CreateOrderRequest request, String userId) {
        // Validación: destinationArea es obligatorio (RFC sección 3.3)
        if (request.destinationArea() == null || request.destinationArea().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "DESTINATION_AREA_REQUIRED");
        }

        List<OrderItem> items = new ArrayList<>();

        for (CreateOrderRequest.OrderItemRequest itemRequest : request.items()) {

            // Validación: quantity debe ser mayor a cero
            if (itemRequest.quantity() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
            }

            Product product = productRepository.findById(itemRequest.productId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "PRODUCT_NOT_FOUND"));

            if (!product.isActive()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "PRODUCT_INACTIVE");
            }

            if (itemRequest.quantity() > product.getMaxQuantityPerOrder()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "QUANTITY_EXCEEDS_LIMIT");
            }

            if (product.getAvailableStock() < itemRequest.quantity()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "INSUFFICIENT_STOCK");
            }

            // Reservar stock con operación atómica $inc (RFC sección 7)
            productRepository.updateStock(product.getId(), -itemRequest.quantity(), itemRequest.quantity());

            items.add(new OrderItem(product.getId(), product.getSku(), itemRequest.quantity()));
        }

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
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND"));

        if (order.getStatus() == OrderStatus.COMPLETED
                || order.getStatus() == OrderStatus.CANCELLED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "ORDER_NOT_CANCELLABLE");
        }

        // Devolver stock reservado con operación atómica $inc (RFC sección 7)
        if (order.getItems() != null) {
            for (OrderItem item : order.getItems()) {
                productRepository.updateStock(item.getProductId(), item.getQuantity(), -item.getQuantity());
            }
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelReason(reason);
        Order saved = orderRepository.save(order);

        // TODO: si la orden estaba IN_PROGRESS, publicar order.cancel a Redpanda

        return saved;
    }
}
