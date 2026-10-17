package com.usal.whbackend.api.infra;

import com.usal.whbackend.service.VehicleTelemetryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/infra/vehicles")
@Tag(name = "Infra", description = "Called by the Infra bridge — authenticated by X-Api-Key")
@SecurityRequirement(name = "infra-api-key")
public class InfraVehicleController {

  private final VehicleTelemetryService telemetryService;

  public InfraVehicleController(VehicleTelemetryService telemetryService) {
    this.telemetryService = telemetryService;
  }

  @Operation(
      summary = "Report a vehicle telemetry reading",
      description =
          "Status, battery and an optional error. Faults arrive here too, as `error`, instead of"
              + " on a separate channel. The id in the path is Infra's; the first reading of an id"
              + " creates the vehicle.")
  @ApiResponse(responseCode = "200", description = "Reading applied")
  @ApiResponse(responseCode = "401", description = "Missing or wrong X-Api-Key")
  @PostMapping("/{vehicleId}/telemetry")
  public ResponseEntity<Map<String, String>> reportTelemetry(
      @PathVariable String vehicleId, @RequestBody InfraTelemetryRequest request) {
    VehicleTelemetryService.Fault fault =
        request.error() == null
            ? null
            : new VehicleTelemetryService.Fault(request.error().code(), request.error().message());
    String backendId =
        telemetryService.apply(
            vehicleId, request.status(), request.battery(), fault, request.timestamp());
    return ResponseEntity.ok(Map.of("vehicle_id", backendId));
  }
}
