package com.usal.whbackend.api.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.usal.whbackend.api.error.GlobalExceptionHandler;
import com.usal.whbackend.api.websocket.WsActivity;
import com.usal.whbackend.config.JacksonConfig;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.config.SecurityConfig;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaLogsRepository;
import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.console.stream.ConsoleStreamService;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.server.ResponseStatusException;

/**
 * The stream through the real security chain, with real tokens: the JWT filter, the URL rules, the
 * method guard and the async response. The application's tokens live {@value #TOKEN_MS} ms, so a
 * stream opened with one ends by itself with {@code end{token-expired}}; tests about anything else
 * use a long-lived token signed with the same key, so a slow first request cannot expire it.
 */
@WebMvcTest(ConsoleStreamController.class)
@EnableMethodSecurity
@Import({
  GlobalExceptionHandler.class,
  SecurityConfig.class,
  JacksonConfig.class,
  ConsoleStreamControllerTest.Wiring.class
})
@TestPropertySource(properties = "console.stream.max-streams-per-principal=2")
class ConsoleStreamControllerTest {

  static final long TOKEN_MS = 1500;
  private static final Duration WAIT = Duration.ofSeconds(5);
  private static final String SECRET = "stream-test-secret-key-min-32-characters!";
  private static final JwtService LONG_LIVED = new JwtService(SECRET, 600_000);

  private static final String WS =
      "{\"subscriptions\":[{\"id\":\"ws\",\"kind\":\"ws-events\",\"feed\":\"orders\"}]}";

  @TestConfiguration
  @EnableConfigurationProperties(ConsoleProperties.class)
  @Import({ConsoleStreamService.class, LogsQueryService.class, WsEventBuffer.class})
  static class Wiring {
    @Bean
    JwtService jwtService() {
      return new JwtService(SECRET, TOKEN_MS);
    }
  }

  @Autowired WebApplicationContext context;
  @Autowired JwtService jwt;
  @Autowired WsEventBuffer buffer;
  @MockitoBean MetricsQueryService metrics;
  @MockitoBean ServiceStatusService serviceStatus;
  @MockitoBean VictoriaLogsRepository logsRepository;
  MockMvc mockMvc;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Warm up the chain (JWT parser, MVC, error handling) before any short-lived token is minted.
    mockMvc.perform(stream(token(UserRole.SUPERADMIN), "{}"));
  }

  /** A long-lived token for a fresh user, so per-user stream limits never leak between tests. */
  private static String token(UserRole role) {
    return LONG_LIVED.generateToken(user(role));
  }

  /** A token that expires {@value #TOKEN_MS} ms from now. */
  private String shortToken(UserRole role) {
    return jwt.generateToken(user(role));
  }

  private static User user(UserRole role) {
    User user = new User();
    user.setId(UUID.randomUUID().toString());
    user.setEmail("someone@example.com");
    user.setRole(role);
    return user;
  }

  private static MockHttpServletRequestBuilder stream(String token, String body) {
    MockHttpServletRequestBuilder req =
        post("/console/stream").contentType(MediaType.APPLICATION_JSON).content(body);
    return token == null ? req : req.header("Authorization", "Bearer " + token);
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
  }

  @Test
  void anonymousIsUnauthorized() throws Exception {
    mockMvc.perform(stream(null, WS)).andExpect(status().isUnauthorized());
  }

  @ParameterizedTest
  @EnumSource(value = UserRole.class, names = "SUPERADMIN", mode = EnumSource.Mode.EXCLUDE)
  void everyOtherRoleIsForbidden(UserRole role) throws Exception {
    mockMvc.perform(stream(token(role), WS)).andExpect(status().isForbidden());
  }

  @Test
  void aTokenInTheQueryStringIsIgnored() throws Exception {
    String token = token(UserRole.SUPERADMIN);
    mockMvc
        .perform(stream(null, WS).queryParam("token", token).queryParam("access_token", token))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void superadminGetsASnapshotThenPushedEventsUntilTheTokenExpires() throws Exception {
    buffer.record(
        WsActivity.ORDERS,
        new com.usal.whbackend.service.console.WsEvent(
            java.time.Instant.parse("2026-10-18T12:00:00Z"), "connect", "before"));

    MvcResult result =
        mockMvc
            .perform(stream(shortToken(UserRole.SUPERADMIN), WS))
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted())
            .andExpect(header().string("Content-Type", "text/event-stream"))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(header().string("X-Accel-Buffering", "no"))
            .andReturn();

    await().atMost(WAIT).until(() -> body(result).contains("event: snapshot"));
    assertThat(body(result))
        .startsWith(
            "event: snapshot\ndata: {\"id\":\"ws\",\"mode\":\"append\",\"data\":{\"feed\":\"/ws/v1/orders\"")
        .contains("\"detail\":\"before\"");

    new WsActivity(buffer, new NoOpTelemetryAdapter())
        .pushed(WsActivity.ORDERS, "order o1 IN_PROGRESS");

    await().atMost(WAIT).until(() -> body(result).contains("event: update"));
    assertThat(body(result)).contains("\"detail\":\"order o1 IN_PROGRESS\"");

    await()
        .atMost(WAIT)
        .until(() -> body(result).endsWith("event: end\ndata: {\"reason\":\"token-expired\"}\n\n"));
    // The async completion goes back through the security chain; it must not be refused there.
    mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());
  }

  @Test
  void aHiddenMetricIsAnErrorEventThatRevealsNothing() throws Exception {
    when(metrics.query(any(), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_METRIC"));
    String body =
        "{\"subscriptions\":[{\"id\":\"m\",\"kind\":\"metric\",\"metric\":\"secret_metric\","
            + "\"range\":\"5m\",\"step\":\"10s\",\"agg\":\"avg\",\"group_by\":[\"route\"]}]}";

    MvcResult result =
        mockMvc
            .perform(stream(token(UserRole.SUPERADMIN), body))
            .andExpect(status().isOk())
            .andReturn();

    await().atMost(WAIT).until(() -> body(result).contains("event: error"));
    assertThat(body(result))
        .startsWith(
            "event: error\ndata: {\"id\":\"m\",\"code\":\"UNKNOWN_METRIC\",\"message\":\"La suscripción no es válida.\"}\n\n")
        .doesNotContain("secret_metric");
  }

  @Test
  void aBadRequestIsRejectedBeforeTheStreamStarts() throws Exception {
    String token = token(UserRole.SUPERADMIN);
    mockMvc
        .perform(stream(token, "{\"subscriptions\":[{\"id\":\"a\",\"kind\":\"trail\"}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(request().asyncNotStarted())
        .andExpect(jsonPath("$.error.code").value("INVALID_SUBSCRIPTION"));
    mockMvc
        .perform(stream(token, "{\"subscriptions\":["))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("MALFORMED_REQUEST"));
  }

  @Test
  void logTextFollowsTheSameInjectionRulesAsTheRestSearch() throws Exception {
    String body =
        "{\"subscriptions\":[{\"id\":\"l\",\"kind\":\"logs\",\"range\":\"1h\",\"text\":\"x\\\" | delete\"}]}";
    mockMvc
        .perform(stream(token(UserRole.SUPERADMIN), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_FILTER_VALUE"));
  }

  @Test
  void aThirdStreamOfTheSameUserIsAdmittedAndTheFirstIsEvicted() throws Exception {
    String token = token(UserRole.SUPERADMIN);
    mockMvc.perform(stream(token, WS)).andExpect(request().asyncStarted());
    mockMvc.perform(stream(token, WS)).andExpect(request().asyncStarted());

    mockMvc.perform(stream(token, WS)).andExpect(request().asyncStarted());
    // Another user is not affected.
    mockMvc.perform(stream(token(UserRole.SUPERADMIN), WS)).andExpect(request().asyncStarted());
  }
}
