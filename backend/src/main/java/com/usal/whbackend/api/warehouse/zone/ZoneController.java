package com.usal.whbackend.api.warehouse.zone;

import com.usal.whbackend.service.ZoneService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/warehouse/zones")
@Tag(name = "Warehouse - Zones")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_WAREHOUSE')")
public class ZoneController {

  private final ZoneService zoneService;

  public ZoneController(ZoneService zoneService) {
    this.zoneService = zoneService;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> getZones() {
    return ResponseEntity.ok(
        Map.of("zones", zoneService.getZones().stream().map(ZoneResponse::from).toList()));
  }

  @PostMapping
  public ResponseEntity<ZoneResponse> createZone(@Valid @RequestBody CreateZoneRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ZoneResponse.from(zoneService.createZone(request)));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<ZoneResponse> updateZone(
      @PathVariable String id, @Valid @RequestBody UpdateZoneRequest request) {
    return ResponseEntity.ok(ZoneResponse.from(zoneService.updateZone(id, request)));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteZone(@PathVariable String id) {
    zoneService.deleteZone(id);
    return ResponseEntity.noContent().build();
  }
}
