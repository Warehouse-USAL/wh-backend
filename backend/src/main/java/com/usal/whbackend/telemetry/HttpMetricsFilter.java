package com.usal.whbackend.telemetry;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Times every request and records it under its route template. Registered first in the chain so
 * requests rejected by security (401/403) are counted too. The query string is never read, so
 * tokens passed as {@code ?token=} cannot leak into a tag. Registered by {@link TelemetryConfig}.
 *
 * <p>Both tags stay bounded: the static-resource catch-all {@code /**} (what Spring reports for any
 * unknown path) counts as {@code UNMATCHED}, and a non-standard method as {@code OTHER}. Also keeps
 * the in-flight count read by the {@code http.server.active} gauge.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class HttpMetricsFilter extends OncePerRequestFilter {

  static final String UNMATCHED = "UNMATCHED";

  static final String OTHER = "OTHER";

  private static final String CATCH_ALL = "/**";

  private static final Set<String> METHODS =
      Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  private final TelemetryPort telemetry;
  private final AtomicInteger active = new AtomicInteger();

  public HttpMetricsFilter(TelemetryPort telemetry) {
    this.telemetry = telemetry;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    boolean completed = false;
    active.incrementAndGet();
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      active.decrementAndGet();
      Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String route =
          pattern == null || CATCH_ALL.equals(pattern.toString()) ? UNMATCHED : pattern.toString();
      String method = METHODS.contains(request.getMethod()) ? request.getMethod() : OTHER;
      // A throwing chain has not set the status yet; the container will answer 500.
      int status = completed ? response.getStatus() : 500;
      telemetry.recordHttpRequest(
          new HttpRequestSample(route, method, status, (System.nanoTime() - start) / 1_000_000.0));
    }
  }

  /** Requests currently inside the chain. */
  public int activeRequests() {
    return active.get();
  }
}
