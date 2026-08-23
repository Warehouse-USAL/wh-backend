package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.service.VehicleEventPublisher;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VehicleTelemetryConsumerTest {

  @Mock VehicleRepository vehicleRepository;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  VehicleTelemetryConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new VehicleTelemetryConsumer(vehicleRepository, vehicleEventPublisher);
  }

  @Test
  void consume_updatesVehicleSnapshotAndBroadcasts() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleTelemetryMessage(
                    "vehicle.telemetry",
                    "vhc-1",
                    new VehicleTelemetryMessage.Position(14.2, 9.1),
                    79,
                    "busy",
                    "2026-05-01T10:00:00Z"));

    consumer.consume(message);

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleRepository).save(captor.capture());
    assertEquals(14.2, captor.getValue().getPositionX(), 0.001);
    assertEquals(9.1, captor.getValue().getPositionY(), 0.001);
    assertEquals(79, captor.getValue().getBattery());
    assertEquals(VehicleStatus.BUSY, captor.getValue().getStatus());
    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void consume_malformedPayload_isLoggedAndSwallowed() {
    consumer.consume("{oops");

    verify(vehicleRepository, org.mockito.Mockito.never()).save(any());
    verify(vehicleEventPublisher, org.mockito.Mockito.never()).broadcastVehicleUpdate(any());
  }

  @Test
  void consume_unknownVehicle_isIgnored() throws Exception {
    when(vehicleRepository.findById("ghost")).thenReturn(Optional.empty());

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleTelemetryMessage(
                    "vehicle.telemetry",
                    "ghost",
                    new VehicleTelemetryMessage.Position(1.0, 2.0),
                    80,
                    "IDLE",
                    "2026-05-01T10:00:00Z"));

    consumer.consume(message);

    verify(vehicleRepository, org.mockito.Mockito.never()).save(any());
  }
}
