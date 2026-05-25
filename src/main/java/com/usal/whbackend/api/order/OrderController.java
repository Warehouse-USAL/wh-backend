package com.usal.whbackend.api.order;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.CurrentSecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Order lifecycle management")
@SecurityRequirement(name = "bearer-jwt")
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @Operation(
      summary = "List orders",
      description =
          "Returns paginated orders, optionally filtered by status, date range, or assigned vehicle")
  @ApiResponse(responseCode = "200", description = "Paginated order list")
  @ApiResponse(responseCode = "400", description = "INVALID_STATUS or INVALID_DATE_FORMAT")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getOrders(
      @Parameter(description = "Filter by status: pending, in_progress, completed, cancelled")
          @RequestParam(required = false)
          String status,
      @Parameter(description = "ISO-8601 start date (inclusive), e.g. 2026-01-01T00:00:00Z")
          @RequestParam(required = false)
          String from,
      @Parameter(description = "ISO-8601 end date (inclusive), e.g. 2026-12-31T23:59:59Z")
          @RequestParam(required = false)
          String to,
      @Parameter(description = "Filter by assigned vehicle ID") @RequestParam(required = false)
          String vehicleId,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 50), 1));
    Page<Order> result = orderService.getOrders(status, from, to, vehicleId, pageable);
    return ResponseEntity.ok(
        Map.of(
            "orders", result.getContent().stream().map(OrderResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(summary = "Get order by ID")
  @ApiResponse(responseCode = "200", description = "Order found")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, OrderResponse>> getOrder(@PathVariable String id) {
    return ResponseEntity.ok(Map.of("order", OrderResponse.from(orderService.getOrder(id))));
  }

  @Operation(
      summary = "Create order",
      description =
          "Creates a new order and reserves stock. Requires ADMIN_SALES or ADMIN_WAREHOUSE role.")
  @ApiResponse(responseCode = "201", description = "Order created")
  @ApiResponse(
      responseCode = "400",
      description =
          "DESTINATION_AREA_REQUIRED, ITEMS_REQUIRED, PRODUCT_NOT_FOUND, INSUFFICIENT_STOCK, QUANTITY_EXCEEDS_LIMIT, etc.")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SALES', 'ADMIN_WAREHOUSE')")
  @PostMapping
  public ResponseEntity<Map<String, OrderResponse>> createOrder(
      @RequestBody CreateOrderRequest request,
      @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
    String userId = authentication.getName();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("order", OrderResponse.from(orderService.createOrder(request, userId))));
  }

  @Operation(
      summary = "Cancel order",
      description =
          "Cancels an order and restores reserved stock. Requires ADMIN_WAREHOUSE or ADMIN_SALES role.")
  @ApiResponse(responseCode = "200", description = "Order cancelled")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "ORDER_NOT_CANCELLABLE — already completed or cancelled")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE', 'ADMIN_SALES')")
  @PostMapping("/{id}/cancel")
  public ResponseEntity<Map<String, OrderResponse>> cancelOrder(
      @PathVariable String id,
      @Parameter(description = "Optional cancellation reason") @RequestParam(required = false)
          String reason) {
    return ResponseEntity.ok(
        Map.of("order", OrderResponse.from(orderService.cancelOrder(id, reason))));
  }
}
