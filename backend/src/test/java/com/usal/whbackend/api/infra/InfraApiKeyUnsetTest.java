package com.usal.whbackend.api.infra;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.OrderStatusService;
import com.usal.whbackend.service.VehicleTelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** With no {@code infra.api-key} configured the endpoints must fail closed, not open. */
@WebMvcTest({InfraOrderController.class, InfraVehicleController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class InfraApiKeyUnsetTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean OrderStatusService orderStatusService;
  @MockitoBean VehicleTelemetryService telemetryService;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void aBlankConfiguredKeyRejectsEvenAnEmptyHeader() throws Exception {
    mockMvc
        .perform(
            post("/infra/orders/ord-1/status")
                .header("X-Api-Key", "")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"completed\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void aBlankConfiguredKeyRejectsAMissingHeader() throws Exception {
    mockMvc
        .perform(
            post("/infra/vehicles/vhc-1/telemetry")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"idle\"}"))
        .andExpect(status().isUnauthorized());
  }
}
