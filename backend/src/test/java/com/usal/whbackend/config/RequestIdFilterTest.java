package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

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
}
