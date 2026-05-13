package com.usal.whbackend.api.vehicle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.service.VehicleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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

    @Test
    @WithMockUser(roles = "ADMIN_SALES")
    void getVehicles_withAdminSales_returns403() throws Exception {
        mockMvc.perform(get("/vehicles"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_WAREHOUSE")
    void getVehicles_withAdminWarehouse_returns200() throws Exception {
        mockMvc.perform(get("/vehicles"))
            .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "ADMIN_WAREHOUSE")
    void registerVehicle_withAdminWarehouse_returns403() throws Exception {
        mockMvc.perform(post("/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rover-01\"}"))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN_SYSTEM")
    void registerVehicle_withAdminSystem_returns201() throws Exception {
        mockMvc.perform(post("/vehicles")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rover-01\"}"))
            .andExpect(status().isCreated());
    }
}
