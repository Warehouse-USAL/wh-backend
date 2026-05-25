package com.usal.whbackend.repository.kafka;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
class VehicleErrorConsumerTest {

  @Mock VehicleRepository vehicleRepository;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  VehicleErrorConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new VehicleErrorConsumer(vehicleRepository, vehicleEventPublisher);
  }

  @Test
  void consume_setsVehicleOfflineAndBroadcastsError() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.BUSY);
    when(vehicleRepository.findById("vhc-2")).thenReturn(Optional.of(vehicle));
    when(vehicleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "Sin señal desde hace 30 segundos.",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleRepository).save(captor.capture());
    assert captor.getValue().getStatus() == VehicleStatus.OFFLINE;

    verify(vehicleEventPublisher)
        .broadcastVehicleError(eq("vhc-2"), eq("CONNECTION_LOST"), any(), any());
  }
}
