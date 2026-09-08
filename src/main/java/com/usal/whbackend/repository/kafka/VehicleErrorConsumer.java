package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.ErrorCode;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleStatusChange;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class VehicleErrorConsumer {

  private static final Logger log = LoggerFactory.getLogger(VehicleErrorConsumer.class);

  private final VehicleUpdateExecutor vehicleUpdateExecutor;
  private final VehicleEventPublisher vehicleEventPublisher;
  private final TelemetryPort telemetry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public VehicleErrorConsumer(
      VehicleUpdateExecutor vehicleUpdateExecutor,
      VehicleEventPublisher vehicleEventPublisher,
      TelemetryPort telemetry) {
    this.vehicleUpdateExecutor = vehicleUpdateExecutor;
    this.vehicleEventPublisher = vehicleEventPublisher;
    this.telemetry = telemetry;
  }

  @KafkaListener(topics = "vehicle.error", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      VehicleErrorMessage msg = objectMapper.readValue(payload, VehicleErrorMessage.class);
      vehicleUpdateExecutor
          .apply(
              msg.vehicleId(),
              previous ->
                  new Update()
                      .set("status", VehicleStatus.OFFLINE)
                      .set("lastSeenAt", Instant.parse(msg.timestamp()))
                      // Ends whatever operation window was open, same reasoning as
                      // VehicleTelemetryConsumer: an offline (or errored) vehicle has no ongoing
                      // uptime to report.
                      .set("operationSince", null))
          .ifPresent(
              result -> {
                vehicleEventPublisher.broadcastVehicleError(
                    msg.vehicleId(), msg.errorCode(), msg.message(), msg.timestamp());
                // This is the one place a real fault category is ever available: routine
                // telemetry-driven transitions (VehicleTelemetryConsumer) carry no error_code at
                // all. Guarded like the telemetry consumer's own transition recording, so a
                // vehicle already OFFLINE re-reporting the same error does not inflate the
                // transition counter.
                VehicleStatus previousStatus = result.previousStatus();
                if (previousStatus != VehicleStatus.OFFLINE) {
                  telemetry.recordStatusTransition(
                      new VehicleStatusChange(
                          msg.vehicleId(),
                          previousStatus == null ? "UNKNOWN" : previousStatus.name(),
                          VehicleStatus.OFFLINE.name(),
                          ErrorCode.fromRaw(msg.errorCode()).name()));
                }
              });
    } catch (Exception e) {
      log.warn("Failed to process vehicle.error message: {}", e.getMessage());
    }
  }
}
