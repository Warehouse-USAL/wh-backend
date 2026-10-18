package com.usal.whbackend.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Times every request and records it under its route template. Registered first in the chain so
 * requests rejected by security (401/403) are counted too. The query string is never read, so
 * tokens passed as {@code ?token=} cannot leak into a tag. Registered by {@link TelemetryConfig}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpMetricsFilter extends OncePerRequestFilter {

  static final String UNMATCHED = "UNMATCHED";

  private final TelemetryPort telemetry;

  public HttpMetricsFilter(TelemetryPort telemetry) {
    this.telemetry = telemetry;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String route = pattern == null ? UNMATCHED : pattern.toString();
      // A throwing chain has not set the status yet; the container will answer 500.
      int status = completed ? response.getStatus() : 500;
      telemetry.recordHttpRequest(
          new HttpRequestSample(
              route, request.getMethod(), status, (System.nanoTime() - start) / 1_000_000.0));
    }
  }
}
