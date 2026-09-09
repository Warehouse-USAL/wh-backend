package com.usal.whbackend.api.restock.reception;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.domain.Reception;
import com.usal.whbackend.service.ReceptionService;
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
@RequestMapping("/restock/receptions")
@Tag(name = "Receptions", description = "Remitos de recepción — la única vía que incrementa stock")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class ReceptionController {

  private final ReceptionService receptionService;

  public ReceptionController(ReceptionService receptionService) {
    this.receptionService = receptionService;
  }

  @Operation(
      summary = "Register reception",
      description =
          "Registers the remito and its position assignments atomically, incrementing stock"
              + " (RN-04/05/06/07).")
  @ApiResponse(responseCode = "201", description = "Reception registered")
  @ApiResponse(
      responseCode = "400",
      description =
          "PRODUCT_NOT_FOUND, ASSIGNMENT_QUANTITY_MISMATCH, RESTOCK_ORDER_PRODUCT_MISMATCH")
  @ApiResponse(responseCode = "404", description = "RESTOCK_ORDER_NOT_FOUND, POSITION_NOT_FOUND")
  @ApiResponse(
      responseCode = "409",
      description = "POSITION_ALREADY_OCCUPIED, STOCK_EXCEEDS_CAPACITY")
  @PostMapping
  public ResponseEntity<Map<String, ReceptionResponse>> createReception(
      @Valid @RequestBody CreateReceptionRequest request,
      @CurrentSecurityContext(expression = "authentication") Authentication authentication) {
    Reception reception = receptionService.createReception(request, authentication.getName());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(Map.of("reception", ReceptionResponse.from(reception)));
  }

  @Operation(summary = "List receptions", description = "Paginated, filterable listing.")
  @ApiResponse(responseCode = "200", description = "Paginated reception list")
  @GetMapping
  public ResponseEntity<Map<String, Object>> getReceptions(
      @RequestParam(required = false) String productId,
      @RequestParam(required = false) String restockOrderId,
      @Parameter(description = "ISO-8601 start date (inclusive)") @RequestParam(required = false)
          String from,
      @Parameter(description = "ISO-8601 end date (inclusive)") @RequestParam(required = false)
          String to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "10") int size) {
    Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(Math.min(size, 50), 1));
    Page<Reception> result =
        receptionService.getReceptions(productId, restockOrderId, from, to, pageable);
    return ResponseEntity.ok(
        Map.of(
            "receptions", result.getContent().stream().map(ReceptionResponse::from).toList(),
            "pagination", Pagination.from(result)));
  }

  @Operation(summary = "Get reception by ID", description = "Includes the per-position breakdown.")
  @ApiResponse(responseCode = "200", description = "Reception found")
  @ApiResponse(responseCode = "404", description = "RECEPTION_NOT_FOUND")
  @GetMapping("/{id}")
  public ResponseEntity<Map<String, ReceptionResponse>> getReception(@PathVariable String id) {
    return ResponseEntity.ok(
        Map.of("reception", ReceptionResponse.from(receptionService.getReception(id))));
  }
}
