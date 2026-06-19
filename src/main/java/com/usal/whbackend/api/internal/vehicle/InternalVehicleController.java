package com.usal.whbackend.api.internal.vehicle;

import com.usal.whbackend.api.vehicle.VehicleResponse;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/vehicles")
@Tag(name = "Internal - Vehicles", description = "Internal vehicle state management")
@SecurityRequirement(name = "bearer-jwt")
public class InternalVehicleController {

  private final VehicleService vehicleService;

  public InternalVehicleController(VehicleService vehicleService) {
    this.vehicleService = vehicleService;
  }

  @Operation(
      summary = "Patch vehicle state",
      description =
          "Partially updates a vehicle's state (status, position, battery, etc.). "
              + "All fields are optional — only provided fields are updated. "
              + "Requires ADMIN_SYSTEM role.")
  @ApiResponse(responseCode = "200", description = "Vehicle updated")
  @ApiResponse(responseCode = "400", description = "Invalid status value")
  @ApiResponse(responseCode = "403", description = "Insufficient role")
  @ApiResponse(responseCode = "404", description = "VEHICLE_NOT_FOUND")
  @PreAuthorize("hasAnyRole('SUPERADMIN', 'ADMIN_SYSTEM')")
  @PatchMapping("/{id}")
  public ResponseEntity<VehicleResponse> patchVehicle(
      @PathVariable String id, @RequestBody PatchVehicleRequest request) {
    Vehicle vehicle = vehicleService.updateVehicle(id, request);
    return ResponseEntity.ok(VehicleResponse.from(vehicle));
  }
}
