package com.usal.whbackend.api.vehicle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(VehicleController.class)
class VehicleControllerTest {

  @Autowired MockMvc mockMvc;
  @MockitoBean VehicleService vehicleService;
  @MockitoBean JwtService jwtService;

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicles_returns200() throws Exception {
    mockMvc.perform(get("/vehicles")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_WAREHOUSE")
  void getVehicle_returns200() throws Exception {
    mockMvc.perform(get("/vehicles/test-id")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void registerVehicle_returns201() throws Exception {
    mockMvc
        .perform(
            post("/vehicles").contentType("application/json").content("{\"name\":\"Rover-01\"}"))
        .andExpect(status().isCreated());
  }
}
