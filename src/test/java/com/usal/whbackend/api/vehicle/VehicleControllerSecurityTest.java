package com.usal.whbackend.api.vehicle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VehicleController.class)
@EnableMethodSecurity
@Import(GlobalExceptionHandler.class)
class VehicleControllerSecurityTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    Pageable pageable = PageRequest.of(0, 10);
    when(vehicleService.getVehicles(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), pageable, 0));

    Vehicle sampleVehicle = new Vehicle();
    sampleVehicle.setId("550e8400-e29b-41d4-a716-446655440000");
    sampleVehicle.setName("Rover-01");
    sampleVehicle.setStatus(VehicleStatus.OFFLINE);
    when(vehicleService.registerVehicle(any())).thenReturn(sampleVehicle);
  }

  @Test
  @WithMockUser(roles = "ADMIN_SALES")
  void getVehicles_withAdminSales_returns403() throws Exception {
    mockMvc.perform(get("/vehicles")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_withAdminWarehouse_returns200() throws Exception {
    mockMvc.perform(get("/vehicles")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void registerVehicle_withAdminWarehouse_returns403() throws Exception {
    mockMvc
        .perform(
            post("/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rover-01\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void registerVehicle_withAdminSystem_returns201() throws Exception {
    mockMvc
        .perform(
            post("/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rover-01\"}"))
        .andExpect(status().isCreated());
  }

  // ── SUPERADMIN: should have full access to all vehicle endpoints ───────

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void getVehicles_withSuperadmin_returns200() throws Exception {
    mockMvc.perform(get("/vehicles")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void registerVehicle_withSuperadmin_returns201() throws Exception {
    mockMvc
        .perform(
            post("/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rover-01\"}"))
        .andExpect(status().isCreated());
  }
}
