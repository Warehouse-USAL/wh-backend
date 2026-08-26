package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.PositionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Warehouse - Positions")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class PositionController {

  private final PositionService positionService;
  private final ProductRepository productRepository;

  public PositionController(PositionService positionService, ProductRepository productRepository) {
    this.positionService = positionService;
    this.productRepository = productRepository;
  }

  @GetMapping("/warehouse/lines/{lineId}/positions")
  public ResponseEntity<Map<String, Object>> getPositions(@PathVariable String lineId) {
    return ResponseEntity.ok(
        Map.of(
            "positions",
            positionService.getPositionsByLine(lineId).stream()
                .map(PositionResponse::from)
                .toList()));
  }

  @GetMapping("/warehouse/positions")
  public ResponseEntity<Map<String, Object>> getAllPositions(
      @RequestParam(required = false, defaultValue = "false") boolean occupied) {
    return ResponseEntity.ok(Map.of("positions", positionService.getPositionsFlat(occupied)));
  }

  @GetMapping("/warehouse/positions/{id}")
  public ResponseEntity<PositionDetailResponse> getPosition(@PathVariable String id) {
    Position position = positionService.getPosition(id);
    Product product =
        position.getProductId() != null
            ? productRepository.findById(position.getProductId()).orElse(null)
            : null;
    return ResponseEntity.ok(PositionDetailResponse.from(position, product));
  }

  @PostMapping("/warehouse/lines/{lineId}/positions")
  public ResponseEntity<PositionResponse> createPosition(
      @PathVariable String lineId, @Valid @RequestBody CreatePositionRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(PositionResponse.from(positionService.createPosition(lineId, request)));
  }

  @PatchMapping("/warehouse/positions/{id}")
  public ResponseEntity<PositionResponse> updatePosition(
      @PathVariable String id, @Valid @RequestBody UpdatePositionRequest request) {
    return ResponseEntity.ok(PositionResponse.from(positionService.updatePosition(id, request)));
  }

  @DeleteMapping("/warehouse/positions/{id}")
  public ResponseEntity<Void> deletePosition(@PathVariable String id) {
    positionService.deletePosition(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/warehouse/positions/validate-fit")
  public ResponseEntity<FitValidationResponse> validateFit(
      @Valid @RequestBody ValidateFitRequest request) {
    Product product =
        productRepository
            .findById(request.productId())
            .orElseThrow(
                () ->
                    new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    if (!product.isActive()) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
    }
    double productVolume = product.getVolume();
    double containerVolume = request.size().getVolumeCm3();
    double requiredVolume = productVolume * request.quantity();
    boolean fits = requiredVolume <= containerVolume;
    int maxQuantityAllowed = productVolume > 0 ? (int) (containerVolume / productVolume) : 0;

    return ResponseEntity.ok(
        new FitValidationResponse(
            fits, productVolume, containerVolume, requiredVolume, maxQuantityAllowed));
  }
}
