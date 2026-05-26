package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

  @Mock VehicleRepository vehicleRepository;
  @InjectMocks VehicleService vehicleService;

  @Test
  void getVehicles_returnsPageFromRepository() {
    Pageable pageable = PageRequest.of(0, 10);
    when(vehicleRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Page<Vehicle> result = vehicleService.getVehicles(pageable);

    assertThat(result.getContent()).isEmpty();
    assertThat(result.getTotalElements()).isEqualTo(0);
  }

  @Test
  void getVehicles_delegatesToRepositoryWithCorrectPageable() {
    Pageable pageable = PageRequest.of(1, 5);
    Vehicle v = new Vehicle();
    when(vehicleRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(v), pageable, 6));

    Page<Vehicle> result = vehicleService.getVehicles(pageable);

    assertThat(result.getNumber()).isEqualTo(1);
    assertThat(result.getSize()).isEqualTo(5);
    assertThat(result.getTotalElements()).isEqualTo(6);
  }

  @Test
  void getVehicle_existingId_returnsVehicle() {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("VHC-001");
    vehicle.setName("Rover-01");
    vehicle.setStatus(VehicleStatus.IDLE);
    when(vehicleRepository.findById("VHC-001")).thenReturn(Optional.of(vehicle));

    Vehicle result = vehicleService.getVehicle("VHC-001");

    assertEquals("VHC-001", result.getId());
    assertEquals("Rover-01", result.getName());
    assertEquals(VehicleStatus.IDLE, result.getStatus());
  }

  @Test
  void getVehicle_unknownId_throws404() {
    when(vehicleRepository.findById("no-existe")).thenReturn(Optional.empty());

    ResponseStatusException ex =
        assertThrows(ResponseStatusException.class, () -> vehicleService.getVehicle("no-existe"));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("VEHICLE_NOT_FOUND", ex.getReason());
  }

  @Test
  void registerVehicle_createsAndSaves() {
    RegisterVehicleRequest request = new RegisterVehicleRequest("Rover-03");

    Vehicle saved = new Vehicle();
    saved.setName("Rover-03");
    saved.setStatus(VehicleStatus.OFFLINE);
    when(vehicleRepository.save(any(Vehicle.class)))
        .thenAnswer(
            inv -> {
              Vehicle v = inv.getArgument(0);
              saved.setId(v.getId());
              return saved;
            });

    Vehicle result = vehicleService.registerVehicle(request);

    assertNotNull(result.getId());
    assertTrue(result.getId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    assertEquals("Rover-03", result.getName());
    assertEquals(VehicleStatus.OFFLINE, result.getStatus());
    verify(vehicleRepository).save(any(Vehicle.class));
  }
}
