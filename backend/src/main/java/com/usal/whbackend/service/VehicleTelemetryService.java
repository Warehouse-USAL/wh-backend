package com.usal.whbackend.service;

import com.usal.whbackend.domain.VehicleIds;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import com.usal.whbackend.telemetry.ErrorCode;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleSample;
import com.usal.whbackend.telemetry.VehicleStatusChange;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Applies one telemetry reading reported by Infra to a vehicle: snapshot fields, operation window,
 * status transition metrics and, when the reading carries a fault, the error broadcast.
 */
@Service
public class VehicleTelemetryService {

  private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryService.class);

  /** A fault attached to a reading ({@code error} in the wire format). */
  public record Fault(String code, String message) {}

  private final VehicleUpdateExecutor vehicleUpdateExecutor;
  private final VehicleEventPublisher vehicleEventPublisher;
  private final TelemetryPort telemetry;

  public VehicleTelemetryService(
      VehicleUpdateExecutor vehicleUpdateExecutor,
      VehicleEventPublisher vehicleEventPublisher,
      TelemetryPort telemetry) {
    this.vehicleUpdateExecutor = vehicleUpdateExecutor;
    this.vehicleEventPublisher = vehicleEventPublisher;
    this.telemetry = telemetry;
  }

  /**
   * The first reading of a vehicle creates it, so the fleet needs no registration step on Infra's
   * side.
   *
   * @param infraVehicleId the id Infra uses for the vehicle; the backend id is derived from it
   * @param battery percentage, or null to keep the last known one
   * @param fault null when the vehicle reports no error
   * @return the backend id of the vehicle
   */
  public String apply(
      String infraVehicleId, String status, Integer battery, Fault fault, String timestamp) {
    String vehicleId = VehicleIds.fromInfra(infraVehicleId);
    vehicleUpdateExecutor.ensureExists(vehicleId, infraVehicleId.trim());
    // A reading with a fault but no usable status still means the vehicle is in trouble.
    VehicleStatus reported = parseStatus(status);
    VehicleStatus current = reported != null || fault == null ? reported : VehicleStatus.ERROR;
    Instant seenAt = parseTimestamp(timestamp);

    vehicleUpdateExecutor
        .apply(
            vehicleId,
            previous -> {
              Update update = new Update().set("lastSeenAt", seenAt);
              if (battery != null) {
                // A percentage. Infra derives it from a voltage and can overshoot; the reading is
                // still real, so it is clamped rather than refused (a 4xx would drop its status).
                update.set("battery", Math.max(0, Math.min(100, battery)));
              }
              if (current != null) {
                update.set("status", current);
                // Coming back online starts a fresh operation window: a vehicle that was
                // offline/errored for a while and just reconnected has zero hours of operation,
                // not the whole down-window's worth. Going offline (or into a self-reported
                // error) ends whatever window was open, so "hours in operation" never keeps
                // climbing for a vehicle that is actually down.
                if ((previous.getStatus() == VehicleStatus.OFFLINE
                        || previous.getStatus() == VehicleStatus.ERROR)
                    && (current == VehicleStatus.IDLE || current == VehicleStatus.BUSY)) {
                  update.set("operationSince", seenAt);
                } else if (current == VehicleStatus.OFFLINE || current == VehicleStatus.ERROR) {
                  update.set("operationSince", null);
                }
              }
              return update;
            })
        .ifPresent(
            result -> {
              vehicleEventPublisher.broadcastVehicleUpdate(result.updated());
              if (fault != null) {
                vehicleEventPublisher.broadcastVehicleError(
                    vehicleId, fault.code(), fault.message(), seenAt.toString());
              }
              recordMetrics(vehicleId, result, current, fault);
            });
    return vehicleId;
  }

  /**
   * Observational only, and done last: persisting and broadcasting the reading must not be undone
   * by a failing metrics exporter, or the bridge would retry a reading that was already applied.
   *
   * <p>The sample is keyed on known vehicles only, which bounds label cardinality to the fleet size
   * rather than to whatever ids the producer happens to send. A transition is recorded only on an
   * actual change: vehicles report continuously, so counting every reading would make the
   * transition counter a message counter, and every failure rate derived from it meaningless. The
   * fault code is the one place a real failure category is available.
   */
  private void recordMetrics(
      String vehicleId, VehicleUpdateExecutor.Result result, VehicleStatus current, Fault fault) {
    try {
      telemetry.recordVehicleSample(new VehicleSample(vehicleId, result.updated().getBattery()));
      VehicleStatus previousStatus = result.previousStatus();
      if (current != null && previousStatus != current) {
        telemetry.recordStatusTransition(
            new VehicleStatusChange(
                vehicleId,
                previousStatus == null ? "UNKNOWN" : previousStatus.name(),
                current.name(),
                fault == null
                    ? VehicleStatusChange.UNCATEGORIZED
                    : ErrorCode.fromRaw(fault.code()).name()));
      }
    } catch (RuntimeException e) {
      log.warn("Failed to record telemetry for vehicle {}: {}", vehicleId, e.getMessage());
    }
  }

  /**
   * An unrecognized status must not sink the whole reading: battery and lastSeenAt are still real
   * and should still be applied even when the producer sends a status this service does not know
   * yet.
   */
  private VehicleStatus parseStatus(String raw) {
    if (raw == null) {
      return null;
    }
    try {
      return VehicleStatus.valueOf(raw.trim().toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unrecognized vehicle status '{}' — applying battery/lastSeenAt only", raw);
      return null;
    }
  }

  private Instant parseTimestamp(String raw) {
    try {
      return Instant.parse(raw);
    } catch (Exception e) {
      log.warn("Missing or malformed telemetry timestamp '{}' — using current time", raw);
      return Instant.now();
    }
  }
}
