package com.usal.whbackend.repository.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.service.VehicleEventPublisher;
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
  private final ObjectMapper objectMapper = new ObjectMapper();

  public VehicleErrorConsumer(
      VehicleRepository vehicleRepository, VehicleEventPublisher vehicleEventPublisher) {
    this.vehicleRepository = vehicleRepository;
    this.vehicleEventPublisher = vehicleEventPublisher;
  }

  @KafkaListener(topics = "vehicle.error", groupId = "wh-backend")
  public void consume(String payload) {
    try {
      VehicleErrorMessage msg = objectMapper.readValue(payload, VehicleErrorMessage.class);
      vehicleRepository
          .findById(msg.vehicleId())
          .ifPresent(
              vehicle -> {
                vehicle.setStatus(VehicleStatus.OFFLINE);
                vehicle.setLastSeenAt(Instant.parse(msg.timestamp()));
                vehicleRepository.save(vehicle);
                vehicleEventPublisher.broadcastVehicleError(
                    msg.vehicleId(), msg.errorCode(), msg.message(), msg.timestamp());
              });
    } catch (Exception e) {
      log.warn("Failed to process vehicle.error message: {}", e.getMessage());
    }
  }
}
