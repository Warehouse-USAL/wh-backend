package com.usal.whbackend.api.restock.order;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.RestockOrder;
import com.usal.whbackend.service.RestockOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/restock/orders")
@Tag(name = "Restock Orders", description = "Pedidos de reposición a proveedor — no afectan stock")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class RestockOrderController {

  private final RestockOrderService restockOrderService;

  public RestockOrderController(RestockOrderService restockOrderService) {
    this.restockOrderService = restockOrderService;
  }

  @Operation(
      summary = "Create restock order",
      description = "Registers a request to a supplier. Never affects stock (RN-03).")
  @ApiResponse(responseCode = "201", description = "Restock order created")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @PostMapping
  public ResponseEntity<Map<String, RestockOrderResponse>> createRestockOrder(
      @Valid @RequestBody CreateRestockOrderRequest request,
      @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
    RestockOrder order = restockOrderService.createRestockOrder(request, authentication.getName());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("restock_order", RestockOrderResponse.from(order)));
  }

  @Operation(summary = "List restock orders", description = "Paginated, filterable listing.")
  @ApiResponse(responseCode = "200", description = "Paginated restock order list")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getRestockOrders(
      @RequestParam(required = false) String productId,
      @RequestParam(required = false) String supplier,
      @Parameter(description = "ISO-8601 start date (inclusive)") @RequestParam(required = false)
          String from,
      @Parameter(description = "ISO-8601 end date (inclusive)") @RequestParam(required = false)
          String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 50), 1));
    Page<RestockOrder> result =
        restockOrderService.getRestockOrders(productId, supplier, from, to, pageable);
    return ResponseEntity.ok(
        Map.of(
            "restock_orders", result.getContent().stream().map(RestockOrderResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(
      summary = "Get restock order by ID",
      description = "Includes quantity_received_so_far, computed from linked receptions.")
  @ApiResponse(responseCode = "200", description = "Restock order found")
  @ApiResponse(responseCode = "404", description = "RESTOCK_ORDER_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, RestockOrderDetailResponse>> getRestockOrder(
      @PathVariable String id) {
    RestockOrder order = restockOrderService.getRestockOrder(id);
    int receivedSoFar = restockOrderService.computeReceivedSoFar(id);
    return ResponseEntity.ok(
        Map.of("restock_order", RestockOrderDetailResponse.from(order, receivedSoFar)));
  }
}
