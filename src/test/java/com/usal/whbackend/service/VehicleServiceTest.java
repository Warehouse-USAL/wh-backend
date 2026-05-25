package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.repository.VehicleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

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
  void getVehicle_throwsUnsupported() {
    assertThrows(UnsupportedOperationException.class, () -> vehicleService.getVehicle("id-1"));
  }

  @Test
  void registerVehicle_throwsUnsupported() {
    assertThrows(
        UnsupportedOperationException.class,
        () -> vehicleService.registerVehicle(new RegisterVehicleRequest("Rover-01")));
  }
}
