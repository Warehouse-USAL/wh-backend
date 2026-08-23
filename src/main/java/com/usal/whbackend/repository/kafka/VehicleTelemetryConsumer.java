package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleSample;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class VehicleTelemetryConsumer {

  private static final Logger log = LoggerFactory.getLogger(VehicleTelemetryConsumer.class);

  private final VehicleRepository vehicleRepository;
  private final VehicleEventPublisher vehicleEventPublisher;
  private final TelemetryPort telemetry;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public VehicleTelemetryConsumer(
      VehicleRepository vehicleRepository,
      VehicleEventPublisher vehicleEventPublisher,
      TelemetryPort telemetry) {
    this.vehicleRepository = vehicleRepository;
    this.vehicleEventPublisher = vehicleEventPublisher;
    this.telemetry = telemetry;
  }

  @KafkaListener(topics = "vehicle.telemetry", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      VehicleTelemetryMessage msg = objectMapper.readValue(payload, VehicleTelemetryMessage.class);
      vehicleRepository
          .findById(msg.vehicleId())
          .ifPresent(
              vehicle -> {
                vehicle.setPositionX(msg.position().x());
                vehicle.setPositionY(msg.position().y());
                vehicle.setBattery(msg.battery());
                vehicle.setStatus(VehicleStatus.valueOf(msg.status().toUpperCase()));
                vehicle.setLastSeenAt(Instant.parse(msg.timestamp()));
                Vehicle saved = vehicleRepository.save(vehicle);
                vehicleEventPublisher.broadcastVehicleUpdate(saved);
                // Recorded last, and only for a vehicle that exists: persistence is the primary
                // job, and keying the metric on known vehicles bounds label cardinality to the
                // fleet size rather than to whatever ids the producer happens to send.
                telemetry.recordVehicleSample(new VehicleSample(msg.vehicleId(), msg.battery()));
              });
    } catch (Exception e) {
      log.warn("Failed to process vehicle.telemetry message: {}", e.getMessage());
    }
  }
}
