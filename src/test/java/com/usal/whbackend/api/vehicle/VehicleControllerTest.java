package com.usal.whbackend.api.vehicle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(VehicleController.class)
class VehicleControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_returns200WithEnvelope() throws Exception {
    Pageable pageable = PageRequest.of(0, 10);
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    mockMvc
        .perform(get("/vehicles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vehicles").isArray())
        .andExpect(jsonPath("$.pagination.total_elements").value(0))
        .andExpect(jsonPath("$.pagination.page").value(0))
        .andExpect(jsonPath("$.pagination.size").value(10))
        .andExpect(jsonPath("$.pagination.total_pages").value(0));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_sizeExceedsMax_clampsTo50() throws Exception {
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(0);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/vehicles").param("size", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(50));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_negativePage_clampsToZero() throws Exception {
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(0);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/vehicles").param("page", "-1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.page").value(0));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_zeroSize_clampsToOne() throws Exception {
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenAnswer(
            inv -> {
              Pageable p = inv.getArgument(0);
              return new PageImpl<>(List.of(), p, 0);
            });

    mockMvc
        .perform(get("/vehicles").param("size", "0"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pagination.size").value(1));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicle_returns200() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("VHC-001");
    vehicle.setName("Rover-01");
    vehicle.setStatus(VehicleStatus.BUSY);
    vehicle.setPositionX(12.5);
    vehicle.setPositionY(7.3);
    vehicle.setBattery(82);
    when(vehicleService.getVehicle("VHC-001")).thenReturn(vehicle);

    mockMvc
        .perform(get("/vehicles/VHC-001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("VHC-001"))
        .andExpect(jsonPath("$.name").value("Rover-01"))
        .andExpect(jsonPath("$.status").value("busy"))
        .andExpect(jsonPath("$.position.x").value(12.5))
        .andExpect(jsonPath("$.position.y").value(7.3))
        .andExpect(jsonPath("$.battery").value(82));
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicle_unknownId_returns404() throws Exception {
    when(vehicleService.getVehicle("no-existe"))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND"));

    mockMvc.perform(get("/vehicles/no-existe")).andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void registerVehicle_returns201() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("VHC-003");
    vehicle.setName("Rover-03");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    when(vehicleService.registerVehicle(any())).thenReturn(vehicle);

    mockMvc
        .perform(
            post("/vehicles")
                .contentType("application/json")
                .content("{\"id\":\"VHC-003\",\"name\":\"Rover-03\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value("VHC-003"))
        .andExpect(jsonPath("$.name").value("Rover-03"))
        .andExpect(jsonPath("$.status").value("offline"));
  }
}
