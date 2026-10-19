package com.usal.whbackend.config;

import com.usal.whbackend.telemetry.HttpMetricsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the caller's {@code X-Request-Id} when it is safe to log, otherwise a
 * fresh UUID. The id is put in the MDC as {@code request_id} (every log line of the request carries
 * it, shipped logs included) and echoed on the response. Runs right after the metrics filter and
 * ahead of Spring Security, so rejected requests get an id too.
 *
 * <p>Also writes one access line per request, {@code http POST /orders/{id} 201 12ms}, while the id
 * is still in the MDC: the route template (never the raw path or query string, which may carry ids
 * or {@code ?token=}), the final status and the duration. INFO below 400, WARN for 4xx and ERROR
 * for 5xx, so failures stand out in the log store even when no handler logged them. Console
 * polling, metric queries, probes, websocket upgrades and vehicle telemetry are not logged: they
 * repeat every few seconds and would flood the store. The method is one of the seven standard ones
 * or {@code OTHER}, so a forged method cannot inject text into the line. A request rejected before
 * the dispatcher (401/403 from security) has no route and is logged as {@code UNMATCHED}, the same
 * value the metrics use.
 *
 * <p>Async requests: {@link OncePerRequestFilter} skips async re-dispatches, so an async endpoint
 * gets one line when its initial dispatch returns, with the status and duration known at that point
 * (the console push stream is under the quiet {@code /console/} prefix and gets none).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";

  public static final String MDC_KEY = "request_id";

  /** Bounded and free of separators, so a caller cannot forge or break log lines. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

  /** Path prefixes never logged: console self-traffic, actuator probes and websocket upgrades. */
  private static final List<String> QUIET_PATHS = List.of("/actuator", "/console/", "/ws/");

  /** Routes never logged: the console's metric polls and per-message vehicle telemetry. */
  private static final Set<String> QUIET_ROUTES =
      Set.of("/metrics/query", "/infra/vehicles/{vehicleId}/telemetry");

  /** Whether {@code id} may be logged or forwarded as a request id. */
  public static boolean isSafe(String id) {
    return id != null && SAFE_ID.matcher(id).matches();
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String inbound = request.getHeader(HEADER);
    String id = isSafe(inbound) ? inbound : UUID.randomUUID().toString();
    MDC.put(MDC_KEY, id);
    response.setHeader(HEADER, id);
    long start = System.nanoTime();
    boolean completed = false;
    try {
      chain.doFilter(request, response);
      completed = true;
    } finally {
      // A throwing chain has not set the status yet; the container will answer 500.
      logAccess(request, completed ? response.getStatus() : 500, start);
      MDC.remove(MDC_KEY);
    }
  }

  private static void logAccess(HttpServletRequest request, int status, long start) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    String route = HttpMetricsFilter.routeOf(request);
    if (QUIET_ROUTES.contains(route) || QUIET_PATHS.stream().anyMatch(path::startsWith)) {
      return;
    }
    Level level = status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
    log.atLevel(level)
        .log(
            "http {} {} {} {}ms",
            HttpMetricsFilter.methodOf(request),
            route,
            status,
            (System.nanoTime() - start) / 1_000_000);
  }
}
