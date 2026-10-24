package com.usal.whbackend.api.internal.vehicle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(InternalVehicleController.class)
class InternalVehicleControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void patchVehicle_returns200WithUpdatedVehicle() throws Exception {
    Vehicle updated = new Vehicle();
    updated.setId("VHC-001");
    updated.setName("Rover-01");
    updated.setStatus(VehicleStatus.OFFLINE);
    updated.setPositionX(3.0);
    updated.setPositionY(7.0);
    updated.setBattery(20);
    when(vehicleService.updateVehicle(eq("VHC-001"), any())).thenReturn(updated);

    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"status\":\"offline\",\"position_x\":3.0,\"position_y\":7.0,\"battery\":20}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("VHC-001"))
        .andExpect(jsonPath("$.status").value("offline"))
        .andExpect(jsonPath("$.position.x").value(3.0))
        .andExpect(jsonPath("$.position.y").value(7.0))
        .andExpect(jsonPath("$.battery").value(20));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void patchVehicle_unknownId_returns404() throws Exception {
    when(vehicleService.updateVehicle(eq("no-existe"), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "VEHICLE_NOT_FOUND"));

    mockMvc
        .perform(
            patch("/internal/vehicles/no-existe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"idle\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void patchVehicle_emptyBody_returns200() throws Exception {
    Vehicle unchanged = new Vehicle();
    unchanged.setId("VHC-001");
    unchanged.setStatus(VehicleStatus.IDLE);
    when(vehicleService.updateVehicle(eq("VHC-001"), any())).thenReturn(unchanged);

    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk());
  }
}
