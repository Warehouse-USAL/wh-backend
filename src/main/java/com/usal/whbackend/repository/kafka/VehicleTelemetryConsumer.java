package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleSample;
import com.usal.whbackend.telemetry.VehicleStatusChange;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class VehicleTelemetryConsumer {

  private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryConsumer.class);

  private final VehicleUpdateExecutor vehicleUpdateExecutor;
  private final VehicleEventPublisher vehicleEventPublisher;
  private final TelemetryPort telemetry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public VehicleTelemetryConsumer(
      VehicleUpdateExecutor vehicleUpdateExecutor,
      VehicleEventPublisher vehicleEventPublisher,
      TelemetryPort telemetry) {
    this.vehicleUpdateExecutor = vehicleUpdateExecutor;
    this.vehicleEventPublisher = vehicleEventPublisher;
    this.telemetry = telemetry;
  }

  @KafkaListener(topics = "vehicle.telemetry", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      VehicleTelemetryMessage msg = objectMapper.readValue(payload, VehicleTelemetryMessage.class);
      VehicleStatus current = parseStatus(msg.status());
      Instant timestamp = Instant.parse(msg.timestamp());

      vehicleUpdateExecutor
          .apply(
              msg.vehicleId(),
              previous -> {
                Update update =
                    new Update()
                        .set("positionX", msg.position().x())
                        .set("positionY", msg.position().y())
                        .set("battery", msg.battery())
                        .set("lastSeenAt", timestamp);
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
                    update.set("operationSince", timestamp);
                  } else if (current == VehicleStatus.OFFLINE || current == VehicleStatus.ERROR) {
                    update.set("operationSince", null);
                  }
                }
                return update;
              })
          .ifPresent(
              result -> {
                vehicleEventPublisher.broadcastVehicleUpdate(result.updated());
                // Recorded last, and only for a vehicle that exists: persistence is the primary
                // job, and keying the metric on known vehicles bounds label cardinality to the
                // fleet size rather than to whatever ids the producer happens to send.
                telemetry.recordVehicleSample(new VehicleSample(msg.vehicleId(), msg.battery()));
                // Only on an actual change. Rovers publish continuously, so counting every
                // message would make the transition counter a message counter, and every
                // failure rate derived from it meaningless.
                if (current != null) {
                  VehicleStatus previousStatus = result.previousStatus();
                  if (previousStatus != current) {
                    telemetry.recordStatusTransition(
                        new VehicleStatusChange(
                            msg.vehicleId(),
                            previousStatus == null ? "UNKNOWN" : previousStatus.name(),
                            current.name(),
                            VehicleStatusChange.UNCATEGORIZED));
                  }
                }
              });
    } catch (Exception e) {
      log.warn("Failed to process vehicle.telemetry message: {}", e.getMessage());
    }
  }

  /**
   * An unrecognized status must not sink the whole message: position/battery/lastSeenAt are still
   * real and should still be applied even when the producer sends a status this consumer does not
   * know yet.
   */
  private VehicleStatus parseStatus(String raw) {
    if (raw == null) {
      log.warn("Missing vehicle status — applying position/battery only");
      return null;
    }
    try {
      return VehicleStatus.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unrecognized vehicle status '{}' — applying position/battery only", raw);
      return null;
    }
  }
}
