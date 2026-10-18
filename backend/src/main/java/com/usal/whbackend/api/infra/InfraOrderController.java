package com.usal.whbackend.api.infra;

import com.usal.whbackend.api.order.OrderResponse;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/infra/orders")
@Tag(name = "Infra", description = "Called by the Infra bridge — authenticated by X-Api-Key")
@SecurityRequirement(name = "infra-api-key")
public class InfraOrderController {

  private static final Logger log = LoggerFactory.getLogger(InfraOrderController.class);

  private final OrderStatusService orderStatusService;

  public InfraOrderController(OrderStatusService orderStatusService) {
    this.orderStatusService = orderStatusService;
  }

  @Operation(
      summary = "Report an order status change",
      description =
          "Infra confirms in_progress (with the vehicle that took the order), completed or"
              + " cancelled. Repeating the current status is a no-op, so redelivery is safe.")
  @ApiResponse(responseCode = "200", description = "Status applied (or already applied)")
  @ApiResponse(responseCode = "400", description = "INVALID_STATUS or VEHICLE_ID_REQUIRED")
  @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key")
  @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
  @ApiResponse(responseCode = "409", description = "ORDER_NOT_MODIFIABLE or VEHICLE_ALREADY_BUSY")
  @PostMapping("/{orderId}/status")
  public ResponseEntity<Map<String, OrderResponse>> reportStatus(
      @PathVariable String orderId, @RequestBody InfraOrderStatusRequest request) {
    Order order =
        orderStatusService.apply(
            orderId, request.status(), request.vehicleId(), request.timestamp());
    // Logged once applied, so the status is a valid one; the bridge forwards the request id.
    log.info("callback order {} status -> {}", orderId, order.getStatus());
    return ResponseEntity.ok(Map.of("order", OrderResponse.from(order)));
  }
}
