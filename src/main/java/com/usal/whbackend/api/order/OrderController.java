package com.usal.whbackend.api.order;

import com.usal.whbackend.api.order.OrderResponse.OrderItemResponse;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping
    public ResponseEntity<List<OrderResponse>> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String vehicleId) {
        List<OrderResponse> response = orderService.getOrders(status, from, to, vehicleId)
                .stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String id) {
        return ResponseEntity.ok(toResponse(orderService.getOrder(id)));
    }

    @PostMapping
    public ResponseEntity<OrderResponse> createOrder(@RequestBody CreateOrderRequest request) {
        // TODO: extraer userId del JWT cuando se implemente autenticación (issue #16)
        String userId = "anonymous";
        Order order = orderService.createOrder(request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(order));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(toResponse(orderService.cancelOrder(id, reason)));
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream()
                        .map(item -> new OrderItemResponse(
                                item.getProductId(), item.getSku(), item.getQuantity()))
                        .toList();

        return new OrderResponse(
                order.getId(),
                order.getStatus(),
                order.getRequestedByUserId(),
                items,
                order.getDestinationArea(),
                order.getAssignedVehicleId(),
                order.getCreatedAt(),
                order.getStartedAt(),
                order.getCompletedAt(),
                order.getCancelReason());
    }
}
