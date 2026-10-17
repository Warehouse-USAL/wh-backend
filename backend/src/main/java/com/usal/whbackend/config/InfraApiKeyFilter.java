package com.usal.whbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates the {@code /infra/**} endpoints, which are called by the bridge rather than by a
 * logged-in user: the {@code X-Api-Key} header must match {@code infra.api-key}. An unset or blank
 * key rejects every call, so a missing {@code INFRA_API_KEY} can never leave the endpoints open.
 */
@Component
public class InfraApiKeyFilter extends OncePerRequestFilter {

  static final String HEADER = "X-Api-Key";
  private static final String PATH_PREFIX = "/infra/";

  private final byte[] expectedKey;

  public InfraApiKeyFilter(@Value("${infra.api-key:}") String apiKey) {
    this.expectedKey = apiKey.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String provided = request.getHeader(HEADER);
    if (expectedKey.length == 0
        || provided == null
        || !MessageDigest.isEqual(expectedKey, provided.getBytes(StandardCharsets.UTF_8))) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response
          .getWriter()
          .write("{\"error\":{\"code\":\"INVALID_API_KEY\",\"message\":\"API key inválida.\"}}");
      return;
    }
    filterChain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith(PATH_PREFIX);
  }
}
