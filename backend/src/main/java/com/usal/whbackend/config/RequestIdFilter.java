package com.usal.whbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id: the caller's {@code X-Request-Id} when it is safe to log, otherwise a
 * fresh UUID. The id is put in the MDC as {@code request_id} (every log line of the request carries
 * it, shipped logs included) and echoed on the response. Runs right after the metrics filter and
 * ahead of Spring Security, so rejected requests get an id too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RequestIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Request-Id";

  public static final String MDC_KEY = "request_id";

  /** Bounded and free of separators, so a caller cannot forge or break log lines. */
  private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

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
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }
}
