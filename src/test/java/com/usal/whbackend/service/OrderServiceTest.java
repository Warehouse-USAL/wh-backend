package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.order.CreateOrderRequest;
import com.usal.whbackend.api.order.CreateOrderRequest.OrderItemRequest;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.OrderRepository;
import com.usal.whbackend.repository.ProductRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock ProductRepository productRepository;
    @InjectMocks OrderService orderService;

    // ── getOrders ──────────────────────────────────────────────────────────────

    @Test
    void getOrders_sinFiltros_retornaTodos() {
        Order o1 = new Order();
        Order o2 = new Order();
        when(orderRepository.findAll()).thenReturn(List.of(o1, o2));

        List<Order> result = orderService.getOrders(null, null, null, null);

        assertEquals(2, result.size());
    }

    @Test
    void getOrders_conFiltroStatus_retornaFiltrados() {
        Order o1 = new Order();
        o1.setStatus(OrderStatus.PENDING);
        when(orderRepository.findByStatus(OrderStatus.PENDING)).thenReturn(List.of(o1));

        List<Order> result = orderService.getOrders("PENDING", null, null, null);

        assertEquals(1, result.size());
        assertEquals(OrderStatus.PENDING, result.get(0).getStatus());
    }

    // ── getOrder ───────────────────────────────────────────────────────────────

    @Test
    void getOrder_existente_retornaOrden() {
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

        Order result = orderService.getOrder("id-1");

        assertEquals(OrderStatus.PENDING, result.getStatus());
    }

    @Test
    void getOrder_noExistente_lanza404() {
        when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.getOrder("no-existe"));

        assertEquals(404, ex.getStatusCode().value());
    }

    // ── createOrder ────────────────────────────────────────────────────────────

    @Test
    void createOrder_valido_creaOrdenPending() {
        Product product = new Product();
        product.setId("prod-1");
        product.setSku("SKU-001");
        product.setActive(true);
        product.setAvailableStock(10);
        product.setReservedStock(0);
        product.setMaxQuantityPerOrder(5);

        when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 3)), "AREA-B");

        Order result = orderService.createOrder(request, "user-1");

        assertEquals(OrderStatus.PENDING, result.getStatus());
        assertEquals("user-1", result.getRequestedByUserId());
        assertEquals("AREA-B", result.getDestinationArea());
        verify(productRepository).updateStock("prod-1", -3, 3);
    }

    @Test
    void createOrder_destinationAreaVacia_lanza400() {
        CreateOrderRequest request = new CreateOrderRequest(List.of(), "");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.createOrder(request, "user-1"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createOrder_cantidadCero_lanza400() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 0)), "AREA-B");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.createOrder(request, "user-1"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createOrder_cantidadNegativa_lanza400() {
        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", -1)), "AREA-B");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.createOrder(request, "user-1"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createOrder_productoInexistente_lanza400() {
        when(productRepository.findById("no-existe")).thenReturn(Optional.empty());

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest("no-existe", 1)), "AREA-B");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.createOrder(request, "user-1"));

        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void createOrder_stockInsuficiente_lanza400() {
        Product product = new Product();
        product.setId("prod-1");
        product.setSku("SKU-001");
        product.setActive(true);
        product.setAvailableStock(2);
        product.setMaxQuantityPerOrder(10);

        when(productRepository.findById("prod-1")).thenReturn(Optional.of(product));

        CreateOrderRequest request = new CreateOrderRequest(
                List.of(new OrderItemRequest("prod-1", 5)), "AREA-B");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.createOrder(request, "user-1"));

        assertEquals(400, ex.getStatusCode().value());
    }

    // ── cancelOrder ────────────────────────────────────────────────────────────

    @Test
    void cancelOrder_pendiente_cancelaCorrectamente() {
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        order.setItems(List.of());
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = orderService.cancelOrder("id-1", "Cancelado por el usuario");

        assertEquals(OrderStatus.CANCELLED, result.getStatus());
        assertEquals("Cancelado por el usuario", result.getCancelReason());
    }

    @Test
    void cancelOrder_completada_lanza409() {
        Order order = new Order();
        order.setStatus(OrderStatus.COMPLETED);
        when(orderRepository.findById("id-1")).thenReturn(Optional.of(order));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class, () -> orderService.cancelOrder("id-1", "motivo"));

        assertEquals(409, ex.getStatusCode().value());
    }

    @Test
    void cancelOrder_noExistente_lanza404() {
        when(orderRepository.findById("no-existe")).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> orderService.cancelOrder("no-existe", "motivo"));

        assertEquals(404, ex.getStatusCode().value());
    }
}
