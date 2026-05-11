package com.usal.whbackend.api.order;

import com.usal.whbackend.service.OrderService;
import java.util.List;
import java.util.Map;
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
    public ResponseEntity<Map<String, List<OrderResponse>>> getOrders(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String vehicleId) {
        List<OrderResponse> orders = orderService.getOrders(status, from, to, vehicleId)
                .stream()
                .map(OrderResponse::from)
                .toList();
        return ResponseEntity.ok(Map.of("orders", orders));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, OrderResponse>> getOrder(@PathVariable String id) {
        return ResponseEntity.ok(Map.of("order", OrderResponse.from(orderService.getOrder(id))));
    }

    @PostMapping
    public ResponseEntity<Map<String, OrderResponse>> createOrder(
            @RequestBody CreateOrderRequest request) {
        // TODO: extraer userId del JWT cuando se implemente autenticación (issue #16)
        String userId = "anonymous";
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("order", OrderResponse.from(orderService.createOrder(request, userId))));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Map<String, OrderResponse>> cancelOrder(
            @PathVariable String id,
            @RequestParam(required = false) String reason) {
        return ResponseEntity.ok(
                Map.of("order", OrderResponse.from(orderService.cancelOrder(id, reason))));
    }
}
