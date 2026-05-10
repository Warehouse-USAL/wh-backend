package com.usal.whbackend.service;

import com.usal.whbackend.api.vehicle.RegisterVehicleRequest;
import com.usal.whbackend.repository.VehicleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class VehicleServiceTest {

    @Mock VehicleRepository vehicleRepository;
    @InjectMocks VehicleService vehicleService;

    @Test
    void getVehicles_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> vehicleService.getVehicles());
    }

    @Test
    void getVehicle_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> vehicleService.getVehicle("id-1"));
    }

    @Test
    void registerVehicle_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> vehicleService.registerVehicle(new RegisterVehicleRequest("Rover-01")));
    }
}
