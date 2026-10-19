package com.usal.whbackend.api.console;

import com.usal.whbackend.service.console.ServiceStatusService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reports whether each platform service is up. */
@RestController
@RequestMapping("/console/status")
@Tag(name = "Console", description = "Backend console (SUPERADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('SUPERADMIN')")
public class ServiceStatusController {

  private final ServiceStatusService service;

  public ServiceStatusController(ServiceStatusService service) {
    this.service = service;
  }

  @Operation(
      summary = "Service status",
      description =
          "State (UP|DOWN|UNKNOWN) of backend, bridge, mongodb, redpanda, minio, victoriametrics,"
              + " victorialogs and otel-collector, probed in parallel. dev is true under the dev"
              + " or docker profile. uptime_ms is set for the backend only.")
  @GetMapping
  public ResponseEntity<Map<String, Object>> status() {
    return ResponseEntity.ok(Map.of("dev", service.isDev(), "services", service.statuses()));
  }
}
