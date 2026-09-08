package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.ErrorCode;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleStatusChange;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class VehicleErrorConsumer {

  private static final Logger log = LoggerFactory.getLogger(VehicleErrorConsumer.class);

  private final VehicleRepository vehicleRepository;
  private final VehicleEventPublisher vehicleEventPublisher;
  private final TelemetryPort telemetry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public VehicleErrorConsumer(
      VehicleRepository vehicleRepository,
      VehicleEventPublisher vehicleEventPublisher,
      TelemetryPort telemetry) {
    this.vehicleRepository = vehicleRepository;
    this.vehicleEventPublisher = vehicleEventPublisher;
    this.telemetry = telemetry;
  }

  @KafkaListener(topics = "vehicle.error", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      VehicleErrorMessage msg = objectMapper.readValue(payload, VehicleErrorMessage.class);
      vehicleRepository
          .findById(msg.vehicleId())
          .ifPresent(
              vehicle -> {
                // Read before the setter overwrites it — the only place this consumer can still
                // see what the vehicle was before the error, same reasoning as
                // VehicleTelemetryConsumer.
                VehicleStatus previous = vehicle.getStatus();
                vehicle.setStatus(VehicleStatus.OFFLINE);
                vehicle.setLastSeenAt(Instant.parse(msg.timestamp()));
                vehicleRepository.save(vehicle);
                vehicleEventPublisher.broadcastVehicleError(
                    msg.vehicleId(), msg.errorCode(), msg.message(), msg.timestamp());
                // This is the one place a real fault category is ever available: routine
                // telemetry-driven transitions (VehicleTelemetryConsumer) carry no error_code at
                // all. Guarded like the telemetry consumer's own transition recording, so a
                // vehicle already OFFLINE re-reporting the same error does not inflate the
                // transition counter.
                if (previous != VehicleStatus.OFFLINE) {
                  telemetry.recordStatusTransition(
                      new VehicleStatusChange(
                          msg.vehicleId(),
                          previous == null ? "UNKNOWN" : previous.name(),
                          VehicleStatus.OFFLINE.name(),
                          ErrorCode.fromRaw(msg.errorCode()).name()));
                }
              });
    } catch (Exception e) {
      log.warn("Failed to process vehicle.error message: {}", e.getMessage());
    }
  }
}
