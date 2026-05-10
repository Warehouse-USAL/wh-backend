package com.usal.whbackend.api.vehicle;

import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleController.class)
@Import(SecurityConfig.class)
class VehicleControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean VehicleService vehicleService;

    @Test
    void getVehicles_returns200() throws Exception {
        mockMvc.perform(get("/vehicles")).andExpect(status().isOk());
    }

    @Test
    void getVehicle_returns200() throws Exception {
        mockMvc.perform(get("/vehicles/test-id")).andExpect(status().isOk());
    }

    @Test
    void registerVehicle_returns200() throws Exception {
        mockMvc.perform(post("/vehicles")
                        .contentType("application/json")
                        .content("{\"name\":\"Rover-01\"}"))
                .andExpect(status().isOk());
    }
}
