package com.usal.whbackend.api.console;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import java.time.Instant;
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

@WebMvcTest(WsEventsController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class WsEventsControllerTest {

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean WsEventBuffer buffer;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void controllerAllowsSuperadmin() throws Exception {
    when(buffer.recent("/ws/v1/orders/{userId}"))
        .thenReturn(List.of(new WsEvent(Instant.parse("2026-10-18T10:00:00Z"), "push", "d")));

    mockMvc
        .perform(get("/console/ws-events/user-orders"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.feed").value("/ws/v1/orders/{userId}"))
        .andExpect(jsonPath("$.events[0].at").value("2026-10-18T10:00:00Z"))
        .andExpect(jsonPath("$.events[0].kind").value("push"))
        .andExpect(jsonPath("$.events[0].detail").value("d"));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void controllerRejectsOtherRoles() throws Exception {
    mockMvc.perform(get("/console/ws-events/orders")).andExpect(status().isForbidden());
  }

  @Test
  void controllerRejectsAnonymous() throws Exception {
    mockMvc.perform(get("/console/ws-events/orders")).andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void unknownFeedIs404() throws Exception {
    mockMvc
        .perform(get("/console/ws-events/nope"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("UNKNOWN_FEED"));
  }
}
