package com.usal.whbackend.api.console;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.console.ServiceStatus;
import com.usal.whbackend.service.console.ServiceStatusService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@WebMvcTest(ServiceStatusController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class ServiceStatusControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean ServiceStatusService service;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void returnsDevFlagAndServicesInSnakeCase() throws Exception {
    when(service.isDev()).thenReturn(true);
    when(service.statuses())
        .thenReturn(
            List.of(
                new ServiceStatus("backend", "UP", null, 1234L),
                new ServiceStatus("bridge", "DOWN", "timeout", null)));

    mockMvc
        .perform(get("/console/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dev").value(true))
        .andExpect(jsonPath("$.services[0].name").value("backend"))
        .andExpect(jsonPath("$.services[0].uptime_ms").value(1234))
        .andExpect(jsonPath("$.services[1].state").value("DOWN"))
        .andExpect(jsonPath("$.services[1].detail").value("timeout"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void controllerIsSuperadminOnly() throws Exception {
    mockMvc.perform(get("/console/status")).andExpect(status().isForbidden());
    verify(service, never()).statuses();
  }

  @Test
  void rejectsAnonymous() throws Exception {
    mockMvc.perform(get("/console/status")).andExpect(status().isUnauthorized());
  }
}
