package com.usal.whbackend.config;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** Stands in for the real actuator endpoints so SecurityConfig rules can be tested in slice. */
@RestController
class ActuatorStubController {
  @GetMapping("/actuator/health")
  String health() {
    return "{\"status\":\"UP\"}";
  }

  @GetMapping("/actuator/info")
  String info() {
    return "{}";
  }
}

@WebMvcTest(controllers = {ActuatorStubController.class})
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class SecurityConfigTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  void actuatorHealth_noToken_returns200() throws Exception {
    mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
  }

  @Test
  void actuatorInfo_noToken_returns200() throws Exception {
    mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
  }
}
