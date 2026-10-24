package com.usal.whbackend.api.console;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.config.JacksonConfig;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaLogsRepository;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The global stream cap answers 429 as JSON, whatever the Accept header asks for. */
@WebMvcTest(ConsoleStreamController.class)
@EnableMethodSecurity
@org.springframework.context.annotation.Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  JacksonConfig.class,
  ConsoleStreamControllerTest.Wiring.class
})
@TestPropertySource(properties = "console.stream.max-streams=1")
class ConsoleStreamGlobalCapTest {

  private static final String SECRET = "stream-test-secret-key-min-32-characters!";
  private static final JwtService LONG_LIVED = new JwtService(SECRET, 600_000);
  private static final String WS =
      "{\"subscriptions\":[{\"id\":\"ws\",\"kind\":\"ws-events\",\"feed\":\"orders\"}]}";

  @Autowired WebApplicationContext context;
  @Autowired WsEventBuffer buffer;
  @MockitoBean MetricsQueryService metrics;
  @MockitoBean ServiceStatusService serviceStatus;
  @MockitoBean VictoriaLogsRepository logsRepository;
  MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  private static String token() {
    User user = new User();
    user.setId(UUID.randomUUID().toString());
    user.setEmail("someone@example.com");
    user.setRole(UserRole.SUPERADMIN);
    return LONG_LIVED.generateToken(user);
  }

  private static MockHttpServletRequestBuilder eventStream(String token) {
    return post("/console/stream")
        .contentType(MediaType.APPLICATION_JSON)
        .content(WS)
        .accept(MediaType.TEXT_EVENT_STREAM)
        .header("Authorization", "Bearer " + token);
  }

  @Test
  void theGlobalCapIsJsonStreamLimitWhateverTheAcceptHeader() throws Exception {
    String first = token();
    mockMvc.perform(eventStream(first)).andExpect(status().isOk());

    mockMvc
        .perform(eventStream(token()))
        .andExpect(status().isTooManyRequests())
        .andExpect(header().string("Content-Type", startsWith("application/json")))
        .andExpect(jsonPath("$.error.code").value("STREAM_LIMIT"));
  }
}
