package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.telemetry.VehicleState;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FleetStateProviderTest {

  @Mock VehicleRepository vehicleRepository;

  private static Vehicle vehicle(String id, VehicleStatus status) {
    Vehicle v = new Vehicle();
    v.setId(id);
    v.setStatus(status);
    return v;
  }

  @Test
  void reportsEveryVehicleWithItsCurrentStatus() {
    when(vehicleRepository.findAll())
        .thenReturn(
            List.of(vehicle("v-1", VehicleStatus.BUSY), vehicle("v-2", VehicleStatus.IDLE)));

    assertThat(new FleetStateProvider(vehicleRepository).currentFleet())
        .containsExactly(new VehicleState("v-1", "BUSY"), new VehicleState("v-2", "IDLE"));
  }

  @Test
  void namesAnAbsentStatusRatherThanPublishingNull() {
    when(vehicleRepository.findAll()).thenReturn(List.of(vehicle("v-1", null)));

    // The status becomes a metric label. A null would be dropped by the SDK, silently changing
    // the series identity instead of showing up as an unknown state.
    assertThat(new FleetStateProvider(vehicleRepository).currentFleet())
        .containsExactly(new VehicleState("v-1", "UNKNOWN"));
  }

  @Test
  void anEmptyFleetIsValid() {
    when(vehicleRepository.findAll()).thenReturn(List.of());

    assertThat(new FleetStateProvider(vehicleRepository).currentFleet()).isEmpty();
  }

  @Test
  void publishesEveryKnownStateSoTheGaugeCanZeroFillTheRest() {
    assertThat(new FleetStateProvider(vehicleRepository).knownStates())
        .containsExactlyElementsOf(Arrays.stream(VehicleStatus.values()).map(Enum::name).toList());
  }
}
