package com.usal.whbackend.api.stock;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.StockAdjustment;
import com.usal.whbackend.service.StockAdjustmentResult;
import com.usal.whbackend.service.StockAdjustmentService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Stock adjustments", description = "Manual stock changes and their history")
@SecurityRequirement(name = "bearer-jwt")
public class StockAdjustmentController {

  private final StockAdjustmentService stockAdjustmentService;

  public StockAdjustmentController(StockAdjustmentService stockAdjustmentService) {
    this.stockAdjustmentService = stockAdjustmentService;
  }

  @Operation(
      summary = "Reduce a position's stock",
      description =
          "Lowers the stock by quantity (a whole number above zero, not above the position's"
              + " stock; minimum and reserved stock are ignored), records it in the history and, in"
              + " the same transaction, re-checks the product's orders. In-progress orders are"
              + " respected; pending ones are walked oldest to newest, keeping each that fits"
              + " entirely and cancelling the rest with reason 'INSUFFICIENT_STOCK: ...'."
              + " Requires ADMIN_WAREHOUSE.")
  @ApiResponse(
      responseCode = "201",
      description = "Adjustment recorded; cancelled_orders lists the orders it cancelled")
  @ApiResponse(
      responseCode = "400",
      description =
          "INVALID_QUANTITY, INVALID_ADJUSTMENT_TYPE, POSITION_WITHOUT_PRODUCT or"
              + " QUANTITY_EXCEEDS_POSITION_STOCK")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "Position not found")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @PostMapping("/warehouse/positions/{id}/stock-adjustments")
  public ResponseEntity<Map<String, Object>> adjustStock(
      @PathVariable String id, @Valid @RequestBody CreateStockAdjustmentRequest request) {
    StockAdjustmentResult result =
        stockAdjustmentService.adjust(id, request.quantity(), request.type(), request.reason());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            Map.of(
                "adjustment", StockAdjustmentResponse.from(result.adjustment()),
                "cancelled_orders",
                    result.cancelledOrders().stream().map(CancelledOrderResponse::from).toList()));
  }

  @Operation(
      summary = "List a position's stock adjustments",
      description = "Newest first. Requires ADMIN_WAREHOUSE.")
  @ApiResponse(responseCode = "200", description = "Paginated adjustment history")
  @ApiResponse(responseCode = "404", description = "Position not found")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
  @GetMapping("/warehouse/positions/{id}/stock-adjustments")
  public ResponseEntity<Map<String, Object>> getPositionAdjustments(
      @PathVariable String id,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(
        page(stockAdjustmentService.getPositionHistory(id, pageable(page, size))));
  }

  @Operation(
      summary = "List a product's stock adjustments",
      description = "Adjustments across all the product's positions, newest first.")
  @ApiResponse(responseCode = "200", description = "Paginated adjustment history")
  @ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
  @GetMapping("/products/{id}/stock-adjustments")
  public ResponseEntity<Map<String, Object>> getProductAdjustments(
      @PathVariable String id,
      @Parameter(description = "Zero-indexed page number") @RequestParam(defaultValue = "0")
          int page,
      @Parameter(description = "Page size (max 50)") @RequestParam(defaultValue = "10") int size) {
    return ResponseEntity.ok(
        page(stockAdjustmentService.getProductHistory(id, pageable(page, size))));
  }

  private static Pageable pageable(int page, int size) {
    return PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 50), 1));
  }

  private static Map<String, Object> page(Page<StockAdjustment> result) {
    return Map.of(
        "stock_adjustments",
            result.getContent().stream().map(StockAdjustmentResponse::from).toList(),
        "pagination", Pagination.from(result));
  }
}
