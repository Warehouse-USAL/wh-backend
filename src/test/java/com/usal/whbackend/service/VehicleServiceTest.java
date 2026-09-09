package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.internal.vehicle.PatchVehicleRequest;
import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VehicleRepository;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
import java.time.Instant;
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
  @Mock VehicleEventPublisher vehicleEventPublisher;
  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
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
    assertTrue(
        result.getId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
    assertEquals("Rover-03", result.getName());
    assertEquals(VehicleStatus.OFFLINE, result.getStatus());
    verify(vehicleRepository).save(any(Vehicle.class));
  }

  @Test
  void updateVehicle_appliesOnlyProvidedFields() {
    Vehicle updated = new Vehicle();
    updated.setId("VHC-001");
    updated.setStatus(VehicleStatus.OFFLINE);
    updated.setPositionX(1.0);
    updated.setPositionY(2.0);
    updated.setBattery(30);
    when(vehicleUpdateExecutor.apply(eq("VHC-001"), any()))
        .thenReturn(Optional.of(new VehicleUpdateExecutor.Result(VehicleStatus.IDLE, updated)));

    PatchVehicleRequest request = new PatchVehicleRequest("offline", null, null, 30, null, null);
    Vehicle result = vehicleService.updateVehicle("VHC-001", request);

    assertEquals(VehicleStatus.OFFLINE, result.getStatus());
    assertEquals(1.0, result.getPositionX());
    assertEquals(2.0, result.getPositionY());
    assertEquals(30, result.getBattery());
    verify(vehicleEventPublisher).broadcastVehicleUpdate(result);
  }

  @Test
  void updateVehicle_allFields_updatesAll() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Vehicle updated = new Vehicle();
    updated.setId("VHC-002");
    updated.setStatus(VehicleStatus.BUSY);
    updated.setPositionX(5.0);
    updated.setPositionY(10.0);
    updated.setBattery(80);
    updated.setCurrentOrderId("ORDER-99");
    updated.setLastSeenAt(now);
    when(vehicleUpdateExecutor.apply(eq("VHC-002"), any()))
        .thenReturn(Optional.of(new VehicleUpdateExecutor.Result(VehicleStatus.IDLE, updated)));

    PatchVehicleRequest request = new PatchVehicleRequest("busy", 5.0, 10.0, 80, "ORDER-99", now);
    Vehicle result = vehicleService.updateVehicle("VHC-002", request);

    assertEquals(VehicleStatus.BUSY, result.getStatus());
    assertEquals(5.0, result.getPositionX());
    assertEquals(10.0, result.getPositionY());
    assertEquals(80, result.getBattery());
    assertEquals("ORDER-99", result.getCurrentOrderId());
    assertEquals(now, result.getLastSeenAt());
  }

  @Test
  void updateVehicle_unknownId_throws404() {
    when(vehicleUpdateExecutor.apply(eq("no-existe"), any())).thenReturn(Optional.empty());

    PatchVehicleRequest request = new PatchVehicleRequest("idle", null, null, null, null, null);
    ResponseStatusException ex =
        assertThrows(
            ResponseStatusException.class,
            () -> vehicleService.updateVehicle("no-existe", request));

    assertEquals(404, ex.getStatusCode().value());
    assertEquals("VEHICLE_NOT_FOUND", ex.getReason());
  }

  @Test
  void updateVehicle_emptyPatch_returnsCurrentVehicleWithoutCallingExecutor() {
    Vehicle existing = new Vehicle();
    existing.setId("VHC-003");
    existing.setStatus(VehicleStatus.IDLE);
    when(vehicleRepository.findById("VHC-003")).thenReturn(Optional.of(existing));

    PatchVehicleRequest request = new PatchVehicleRequest(null, null, null, null, null, null);
    Vehicle result = vehicleService.updateVehicle("VHC-003", request);

    assertEquals("VHC-003", result.getId());
    verify(vehicleUpdateExecutor, never()).apply(any(), any());
    verify(vehicleEventPublisher).broadcastVehicleUpdate(result);
  }
}
