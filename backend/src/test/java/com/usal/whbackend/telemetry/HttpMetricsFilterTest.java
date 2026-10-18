package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class HttpMetricsFilterTest {

  private final List<HttpRequestSample> samples = new ArrayList<>();
  private final TelemetryPort port =
      new NoOpTelemetryAdapter() {
        @Override
        public void recordHttpRequest(HttpRequestSample sample) {
          samples.add(sample);
        }
      };
  private final HttpMetricsFilter filter = new HttpMetricsFilter(port);

  @Test
  void nullMethodIsTaggedOtherInsteadOfThrowing() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
    request.setMethod(null);

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

    assertThat(samples).hasSize(1);
    assertThat(samples.get(0).method()).isEqualTo("OTHER");
  }

  @Test
  void filterRecordsRouteTemplateNotRawPath() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/orders/o_7f3a");
    request.setQueryString("token=secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/orders/{id}");
            ((MockHttpServletResponse) res).setStatus(200);
          }
        };

    filter.doFilter(request, response, chain);

    assertThat(samples).hasSize(1);
    HttpRequestSample sample = samples.get(0);
    assertThat(sample.route()).isEqualTo("/orders/{id}");
    assertThat(sample.method()).isEqualTo("GET");
    assertThat(sample.status()).isEqualTo(200);
    assertThat(sample.durationMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  void unmatchedRequestsGetUnmatchedRoute() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/wp-admin");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            ((MockHttpServletResponse) res).setStatus(404);
          }
        };

    filter.doFilter(request, response, chain);

    assertThat(samples)
        .singleElement()
        .satisfies(s -> assertThat(s.route()).isEqualTo("UNMATCHED"));
    assertThat(samples.get(0).status()).isEqualTo(404);
  }

  @Test
  void staticResourceCatchAllCountsAsUnmatched() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/no/such/thing");
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");
            ((MockHttpServletResponse) res).setStatus(404);
          }
        };

    filter.doFilter(request, new MockHttpServletResponse(), chain);

    assertThat(samples)
        .singleElement()
        .satisfies(s -> assertThat(s.route()).isEqualTo("UNMATCHED"));
  }

  @Test
  void nonStandardMethodsCollapseToOther() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("PROPFIND", "/x");

    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    filter.doFilter(
        new MockHttpServletRequest("PATCH", "/x"),
        new MockHttpServletResponse(),
        new MockFilterChain());

    assertThat(samples).extracting(HttpRequestSample::method).containsExactly("OTHER", "PATCH");
  }

  @Test
  void filterRecordsEvenWhenChainThrows() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/boom");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain =
        new MockFilterChain() {
          @Override
          public void doFilter(
              jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            throw new IllegalStateException("boom");
          }
        };

    assertThatThrownBy(() -> filter.doFilter(request, response, chain))
        .isInstanceOf(IllegalStateException.class);

    assertThat(samples).hasSize(1);
    assertThat(samples.get(0).status()).isEqualTo(500);
    assertThat(samples.get(0).method()).isEqualTo("POST");
  }
}
