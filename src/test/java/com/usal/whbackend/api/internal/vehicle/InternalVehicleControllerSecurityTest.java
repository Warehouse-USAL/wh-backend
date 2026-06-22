package com.usal.whbackend.api.internal.vehicle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InternalVehicleController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class InternalVehicleControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  private static final String BODY = "{\"status\":\"idle\"}";

  @BeforeEach
  void setUp() {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("VHC-001");
    vehicle.setStatus(VehicleStatus.IDLE);
    when(vehicleService.updateVehicle(eq("VHC-001"), any())).thenReturn(vehicle);
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void patchVehicle_withAdminSystem_returns200() throws Exception {
    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void patchVehicle_withSuperadmin_returns200() throws Exception {
    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void patchVehicle_withAdminWarehouse_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void patchVehicle_withAdminSales_returns403() throws Exception {
    mockMvc
        .perform(
            patch("/internal/vehicles/VHC-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isForbidden());
  }
}
