package com.usal.whbackend.api.vehicle;

import com.usal.whbackend.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Collections;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/vehicles")
@Tag(name = "Vehicles", description = "Autonomous vehicle fleet management")
@SecurityRequirement(name = "bearer-jwt")
public class VehicleController {

  private final VehicleService vehicleService;

  public VehicleController(VehicleService vehicleService) {
    this.vehicleService = vehicleService;
  }

  @Operation(summary = "List vehicles", description = "Requires ADMIN_SYSTEM or ADMIN_WAREHOUSE role")
  @ApiResponse(responseCode = "200", description = "Vehicle list")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasAnyRole('ADMIN_SYSTEM', 'ADMIN_WAREHOUSE')")
  @GetMapping
  public ResponseEntity<List<VehicleResponse>> getVehicles() {
    return ResponseEntity.ok(Collections.emptyList());
  }

  @Operation(summary = "Get vehicle by ID", description = "Requires ADMIN_SYSTEM or ADMIN_WAREHOUSE role")
  @ApiResponse(responseCode = "200", description = "Vehicle found")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "VEHICLE_NOT_FOUND")
  @PreAuthorize("hasAnyRole('ADMIN_SYSTEM', 'ADMIN_WAREHOUSE')")
  @GetMapping("/{id}")
  public ResponseEntity<VehicleResponse> getVehicle(@PathVariable String id) {
    return ResponseEntity.ok().build();
  }

  @Operation(summary = "Register vehicle", description = "Registers a new vehicle in the fleet. Requires ADMIN_SYSTEM role.")
  @ApiResponse(responseCode = "201", description = "Vehicle registered")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @PreAuthorize("hasRole('ADMIN_SYSTEM')")
  @PostMapping
  public ResponseEntity<VehicleResponse> registerVehicle(
      @RequestBody RegisterVehicleRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
