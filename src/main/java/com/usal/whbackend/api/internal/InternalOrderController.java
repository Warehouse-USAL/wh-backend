package com.usal.whbackend.api.internal;

import com.usal.whbackend.api.order.OrderResponse;
import com.usal.whbackend.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/orders")
@Tag(name = "Internal", description = "Internal simulation endpoints — not for production use")
@SecurityRequirement(name = "bearer-jwt")
public class InternalOrderController {

  private final OrderService orderService;

  public InternalOrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @Operation(
      summary = "Assign vehicle to order",
      description =
          "Assigns a vehicle to a pending or in-progress order, transitioning it to IN_PROGRESS."
              + " Simulates the asynchronous assignment that would normally arrive via Kafka.")
  @ApiResponse(responseCode = "200", description = "Vehicle assigned")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND or VEHICLE_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "ORDER_NOT_ASSIGNABLE")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @PatchMapping("/{id}/assign-vehicle")
  public ResponseEntity<Map<String, OrderResponse>> assignVehicle(
      @PathVariable String id, @RequestBody AssignVehicleRequest request) {
    return ResponseEntity.ok(
        Map.of("order", OrderResponse.from(orderService.assignVehicle(id, request.vehicleId()))));
  }

  @Operation(
      summary = "Change order status",
      description =
          "Forces an order into the given status (in_progress, completed, or cancelled),"
              + " simulating the asynchronous transition that would normally arrive via Kafka."
              + " Completing an order drains its stock. Reverting to pending is rejected, and"
              + " orders already completed or cancelled cannot be modified.")
  @ApiResponse(responseCode = "200", description = "Status changed")
  @ApiResponse(responseCode = "400", description = "INVALID_STATUS or INVALID_STATUS_TRANSITION")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "ORDER_NOT_MODIFIABLE")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @PatchMapping("/{id}/status")
  public ResponseEntity<Map<String, OrderResponse>> changeStatus(
      @PathVariable String id, @RequestBody ChangeOrderStatusRequest request) {
    return ResponseEntity.ok(
        Map.of("order", OrderResponse.from(orderService.changeStatus(id, request.status()))));
  }
}
