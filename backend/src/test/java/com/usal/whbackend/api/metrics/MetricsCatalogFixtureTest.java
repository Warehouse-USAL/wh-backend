package com.usal.whbackend.api.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.metrics.MetricRegistry;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import com.usal.whbackend.service.metrics.conversion.SuggestionConversionService;
import com.usal.whbackend.service.metrics.restock.RestockSuggestionService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The console's catalogue fixture is what {@code GET /metrics/catalog} serves a SUPERADMIN, so the
 * console's tests can never chart a metric the backend does not offer, or miss one it does.
 *
 * <p>Regenerate after a {@link MetricRegistry} change with {@code UPDATE_CONSOLE_FIXTURES=1
 * ./gradlew test --tests '*MetricsCatalogFixtureTest'} (from backend/), and commit the fixture.
 */
@WebMvcTest(MetricsController.class)
class MetricsCatalogFixtureTest {

  static final String FIXTURE = "console/src/api/__fixtures__/metrics-catalog.json";

  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper mapper;
  @MockitoBean MetricsQueryService metricsQueryService;
  @MockitoBean JwtService jwtService;
  @MockitoBean RestockSuggestionService restockSuggestionService;
  @MockitoBean SuggestionConversionService suggestionConversionService;

  @Test
  @WithMockUser(roles = "SUPERADMIN")
  void theConsoleFixtureIsTheCatalogueASuperadminIsServed() throws Exception {
    MetricRegistry registry = new MetricRegistry();
    Set<UserRole> superadmin = Set.of(UserRole.SUPERADMIN);
    when(metricsQueryService.catalog(any())).thenReturn(registry.visibleTo(superadmin));
    when(metricsQueryService.computedCatalog(any()))
        .thenReturn(registry.computedVisibleTo(superadmin));

    String body =
        mockMvc
            .perform(get("/metrics/catalog"))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    JsonNode served = mapper.readTree(body);
    Path fixture = fixture();

    if (System.getenv("UPDATE_CONSOLE_FIXTURES") != null) {
      // The response is a Map.of, whose key order changes between runs; fix it for stable diffs.
      ObjectNode ordered = mapper.createObjectNode();
      ordered.set("computed_metrics", served.get("computed_metrics"));
      ordered.set("metrics", served.get("metrics"));
      Files.writeString(
          fixture, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(ordered) + "\n");
    }

    assertThat(mapper.readTree(Files.readString(fixture)))
        .as(
            "%s is out of date with MetricRegistry; regenerate it from backend/ with"
                + " UPDATE_CONSOLE_FIXTURES=1 ./gradlew test --tests '*MetricsCatalogFixtureTest'",
            FIXTURE)
        .isEqualTo(served);
  }

  /** Walks up from the working directory (backend/ under Gradle) to the repo's console fixture. */
  private static Path fixture() throws IOException {
    for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
      Path candidate = dir.resolve(FIXTURE);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    throw new IOException(FIXTURE + " not found from " + Path.of("").toAbsolutePath());
  }
}
