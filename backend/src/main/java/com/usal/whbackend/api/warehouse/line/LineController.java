package com.usal.whbackend.api.warehouse.line;

import com.usal.whbackend.service.LineService;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Warehouse - Lines")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class LineController {

  private final LineService lineService;

  public LineController(LineService lineService) {
    this.lineService = lineService;
  }

  @GetMapping("/warehouse/zones/{zoneId}/lines")
  public ResponseEntity<Map<String, Object>> getLines(@PathVariable String zoneId) {
    return ResponseEntity.ok(
        Map.of(
            "lines", lineService.getLinesByZone(zoneId).stream().map(LineResponse::from).toList()));
  }

  @PostMapping("/warehouse/zones/{zoneId}/lines")
  public ResponseEntity<LineResponse> createLine(
      @PathVariable String zoneId, @Valid @RequestBody CreateLineRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(LineResponse.from(lineService.createLine(zoneId, request)));
  }

  @PatchMapping("/warehouse/lines/{id}")
  public ResponseEntity<LineResponse> updateLine(
      @PathVariable String id, @Valid @RequestBody UpdateLineRequest request) {
    return ResponseEntity.ok(LineResponse.from(lineService.updateLine(id, request)));
  }

  @DeleteMapping("/warehouse/lines/{id}")
  public ResponseEntity<Void> deleteLine(@PathVariable String id) {
    lineService.deleteLine(id);
    return ResponseEntity.noContent().build();
  }
}
