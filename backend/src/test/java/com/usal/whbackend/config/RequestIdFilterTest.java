package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class RequestIdFilterTest {

  private final RequestIdFilter filter = new RequestIdFilter();
  private final AtomicReference<String> seenInMdc = new AtomicReference<>();
  private final MockFilterChain chain =
      new MockFilterChain() {
        @Override
        public void doFilter(ServletRequest req, ServletResponse res) {
          seenInMdc.set(MDC.get(RequestIdFilter.MDC_KEY));
        }
      };

  private final Logger logger = (Logger) LoggerFactory.getLogger(RequestIdFilter.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  @AfterEach
  void releaseLogs() {
    logger.detachAppender(logs);
  }

  private MockHttpServletResponse run(String header) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders");
    if (header != null) {
      request.addHeader(RequestIdFilter.HEADER, header);
    }
    MockHttpServletResponse response = new MockHttpServletResponse();
    filter.doFilter(request, response, chain);
    return response;
  }

  @Test
  void acceptsAWellFormedIncomingId() throws Exception {
    MockHttpServletResponse response = run("abc-123_XYZ");

    assertThat(seenInMdc.get()).isEqualTo("abc-123_XYZ");
    assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_XYZ");
  }

  @ParameterizedTest
  @ValueSource(strings = {"has spaces", "line\nbreak", "dot.ted", ""})
  void replacesAnInvalidIdWithAUuid(String invalid) throws Exception {
    assertReplacedWithUuid(invalid);
  }

  @Test
  void replacesAnOverlongIdWithAUuid() throws Exception {
    assertReplacedWithUuid("a".repeat(200));
  }

  private void assertReplacedWithUuid(String invalid) throws Exception {
    MockHttpServletResponse response = run(invalid);

    String id = response.getHeader(RequestIdFilter.HEADER);
    assertThat(id).isNotEqualTo(invalid);
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(seenInMdc.get()).isEqualTo(id);
  }

  @Test
  void responseCarriesTheId() throws Exception {
    MockHttpServletResponse first = run(null);
    MockHttpServletResponse second = run(null);

    String id = first.getHeader(RequestIdFilter.HEADER);
    assertThat(UUID.fromString(id).toString()).isEqualTo(id);
    assertThat(seenInMdc.get()).isEqualTo(second.getHeader(RequestIdFilter.HEADER));
    assertThat(second.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(id);
  }

  @Test
  void mdcIsClearedAfterTheRequest() throws Exception {
    run("abc-123");
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();

    MockFilterChain failing =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, ServletResponse res) throws ServletException {
            throw new ServletException("boom");
          }
        };
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("GET", "/orders"),
                    new MockHttpServletResponse(),
                    failing))
        .isInstanceOf(ServletException.class);
    assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
  }

  /** Right after the metrics filter, ahead of Spring Security (order -100). */
  @Test
  void isRegisteredRightAfterTheMetricsFilter() {
    new ApplicationContextRunner()
        .withBean(RequestIdFilter.class)
        .run(
            context -> {
              List<Integer> orders =
                  new ServletContextInitializerBeans(context.getBeanFactory())
                      .stream()
                          .filter(FilterRegistrationBean.class::isInstance)
                          .map(FilterRegistrationBean.class::cast)
                          .filter(r -> r.getFilter() instanceof RequestIdFilter)
                          .map(FilterRegistrationBean::getOrder)
                          .toList();
              assertThat(orders).containsExactly(Ordered.HIGHEST_PRECEDENCE + 1);
            });
  }

  // ── access line ─────────────────────────────────────────────────────────────

  /** Runs one request whose controller matched {@code route} and answered {@code status}. */
  private void serve(String method, String uri, String route, int status) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
    request.addHeader(RequestIdFilter.HEADER, "req-1");
    MockFilterChain answering =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, ServletResponse res) {
            if (route != null) {
              req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, route);
            }
            ((MockHttpServletResponse) res).setStatus(status);
          }
        };
    filter.doFilter(request, new MockHttpServletResponse(), answering);
  }

  @Test
  void logsOneLineWithTheRouteTemplateStatusAndDuration() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/orders/ord-42");
    request.setQueryString("token=eyJsecret&email=a@b.c");
    request.addHeader("Authorization", "Bearer eyJsecret");
    request.addHeader(RequestIdFilter.HEADER, "req-1");
    MockFilterChain answering =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, ServletResponse res) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
            ((MockHttpServletResponse) res).setStatus(201);
          }
        };
    filter.doFilter(request, new MockHttpServletResponse(), answering);

    assertThat(logs.list).hasSize(1);
    ILoggingEvent line = logs.list.get(0);
    assertThat(line.getFormattedMessage())
        .matches("http POST /orders/\\{id} 201 \\d+ms")
        .doesNotContain("ord-42", "token", "eyJ", "Bearer", "@");
    assertThat(line.getLevel()).isEqualTo(Level.INFO);
    assertThat(line.getMDCPropertyMap()).containsEntry(RequestIdFilter.MDC_KEY, "req-1");
  }

  @ParameterizedTest
  @CsvSource({"200, INFO", "302, INFO", "404, WARN", "409, WARN", "500, ERROR", "503, ERROR"})
  void levelFollowsTheStatus(int status, String level) throws Exception {
    serve("GET", "/orders", "/orders", status);

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getLevel)
        .isEqualTo(Level.toLevel(level));
  }

  @Test
  void aThrowingChainIsLoggedAsA500() {
    MockFilterChain failing =
        new MockFilterChain() {
          @Override
          public void doFilter(ServletRequest req, ServletResponse res) throws ServletException {
            throw new ServletException("boom");
          }
        };
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    new MockHttpServletRequest("GET", "/orders"),
                    new MockHttpServletResponse(),
                    failing))
        .isInstanceOf(ServletException.class);

    assertThat(logs.list)
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.getFormattedMessage()).matches("http GET UNMATCHED 500 \\d+ms");
              assertThat(line.getLevel()).isEqualTo(Level.ERROR);
            });
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {"null", "/**"})
  void anUnmappedPathIsLoggedAsUnmatched(String route) throws Exception {
    serve("GET", "/wp-admin/ord-1", route, 404);

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .matches("http GET UNMATCHED 404 \\d+ms");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PROPFIND", "GET\nhttp POST /admin 200 1ms", "get"})
  void aNonStandardMethodIsLoggedAsOther(String method) throws Exception {
    serve(method, "/orders", "/orders", 200);

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .matches("http OTHER /orders 200 \\d+ms");
  }

  /** Security answers before the dispatcher matches a route, so no route is claimed. */
  @ParameterizedTest
  @ValueSource(ints = {401, 403})
  void aRejectedRequestClaimsNoRoute(int status) throws Exception {
    serve("DELETE", "/products/p-1", null, status);

    assertThat(logs.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .asString()
        .matches("http DELETE UNMATCHED " + status + " \\d+ms");
  }

  @ParameterizedTest
  @CsvSource(
      nullValues = "null",
      value = {
        "/console/stream, /console/stream",
        "/actuator/health, null",
        "/console/logs/query, /console/logs/query",
        "/console/status, /console/status",
        "/console/ws-events/orders, /console/ws-events/{feed}",
        "/ws/v1/orders, null",
        "/metrics/query, /metrics/query",
        "/infra/vehicles/vhc-1/telemetry, /infra/vehicles/{vehicleId}/telemetry"
      })
  void pollingProbesAndTelemetryAreNotLogged(String uri, String route) throws Exception {
    serve("POST", uri, route, 200);

    assertThat(logs.list).isEmpty();
  }

  @Test
  void theContextPathIsIgnoredWhenMatchingQuietPaths() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/console/status");
    request.setContextPath("/api");
    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(logs.list).isEmpty();
  }
}
