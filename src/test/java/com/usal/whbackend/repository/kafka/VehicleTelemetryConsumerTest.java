package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleSample;
import com.usal.whbackend.telemetry.VehicleStatusChange;
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
  @Mock TelemetryPort telemetry;
  VehicleTelemetryConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new VehicleTelemetryConsumer(vehicleRepository, vehicleEventPublisher, telemetry);
  }

  private static String message(String vehicleId, int battery) throws Exception {
    return new ObjectMapper()
        .writeValueAsString(
            new VehicleTelemetryMessage(
                "vehicle.telemetry",
                vehicleId,
                new VehicleTelemetryMessage.Position(14.2, 9.1),
                battery,
                "busy",
                "2026-05-01T10:00:00Z"));
  }

  @Test
  void consume_updatesVehicleSnapshotAndBroadcasts() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(message("vhc-1", 79));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleRepository).save(captor.capture());
    assertEquals(14.2, captor.getValue().getPositionX(), 0.001);
    assertEquals(9.1, captor.getValue().getPositionY(), 0.001);
    assertEquals(79, captor.getValue().getBattery());
    assertEquals(VehicleStatus.BUSY, captor.getValue().getStatus());
    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void consume_recordsBatteryTelemetry() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(message("vhc-1", 79));

    verify(telemetry).recordVehicleSample(new VehicleSample("vhc-1", 79));
  }

  @Test
  void consume_doesNotRecordTelemetryForAnUnknownVehicle() throws Exception {
    when(vehicleRepository.findById("ghost")).thenReturn(Optional.empty());

    consumer.consume(message("ghost", 42));

    verify(telemetry, never()).recordVehicleSample(any());
  }

  @Test
  void consume_persistsAndBroadcastsEvenIfTelemetryBlowsUp() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    doThrow(new IllegalStateException("exporter exploded"))
        .when(telemetry)
        .recordVehicleSample(any());

    consumer.consume(message("vhc-1", 79));

    // Telemetry is recorded last precisely so a failure here cannot cost us the snapshot.
    verify(vehicleRepository).save(any(Vehicle.class));
    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void recordsATransitionWhenTheStatusActuallyChanges() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.IDLE);
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    consumer.consume(message("vhc-1", 79));

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("IDLE", captor.getValue().from());
    assertEquals("BUSY", captor.getValue().to());
    assertEquals(VehicleStatusChange.UNCATEGORIZED, captor.getValue().category());
  }

  @Test
  void recordsNoTransitionWhenTheStatusIsUnchanged() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.BUSY);
    when(vehicleRepository.findById("vhc-1")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // Rovers publish continuously. Counting every message would turn the transition counter into
    // a message counter and make every failure rate derived from it meaningless.
    consumer.consume(message("vhc-1", 79));

    verify(telemetry, never()).recordStatusTransition(any());
  }
}
