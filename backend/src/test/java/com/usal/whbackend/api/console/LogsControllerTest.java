package com.usal.whbackend.api.console;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.service.console.LogLine;
import com.usal.whbackend.service.console.LogsQuery;
import com.usal.whbackend.service.console.LogsQueryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(LogsController.class)
@EnableMethodSecurity
@Import({GlobalExceptionHandler.class, SecurityConfig.class})
class LogsControllerTest {

  private static final String BODY =
      """
      {"service":"wh-bridge","level":"error","request_id":"7f3a","text":"timeout",
       "from":"2026-10-18T10:00:00Z","to":"2026-10-18T11:00:00Z","limit":20}
      """;

  @Autowired WebApplicationContext context;
  MockMvc mockMvc;
  @MockitoBean LogsQueryService service;
  @MockitoBean JwtService jwtService;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void returnsLinesAndTotalInSnakeCase() throws Exception {
    when(service.query(any()))
        .thenReturn(
            List.of(
                new LogLine(
                    Instant.parse("2026-10-18T10:30:00Z"), "error", "wh-bridge", "boom", "7f3a")));

    mockMvc
        .perform(post("/console/logs/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.lines[0].time").value("2026-10-18T10:30:00Z"))
        .andExpect(jsonPath("$.lines[0].request_id").value("7f3a"))
        .andExpect(jsonPath("$.lines[0].message").value("boom"));

    ArgumentCaptor<LogsQuery> captor = ArgumentCaptor.forClass(LogsQuery.class);
    verify(service).query(captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue())
        .isEqualTo(
            new LogsQuery(
                "wh-bridge",
                "error",
                "7f3a",
                "timeout",
                Instant.parse("2026-10-18T10:00:00Z"),
                Instant.parse("2026-10-18T11:00:00Z"),
                20));
  }

  @Test
  @WithMockUser(roles = "ADMIN_SYSTEM")
  void controllerIsSuperadminOnly() throws Exception {
    mockMvc
        .perform(post("/console/logs/query").contentType(MediaType.APPLICATION_JSON).content(BODY))
        .andExpect(status().isForbidden());
    verify(service, never()).query(any());
  }

  @Test
  void rejectsAnonymous() throws Exception {
    mockMvc
        .perform(post("/console/logs/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void storeDownReturns502LogStoreUnavailable() throws Exception {
    when(service.query(any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LOG_STORE_UNAVAILABLE"));

    mockMvc
        .perform(post("/console/logs/query").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("LOG_STORE_UNAVAILABLE"));
  }
}
