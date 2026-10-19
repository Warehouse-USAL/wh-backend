package com.usal.whbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

  /**
   * Request attribute holding the {@link java.time.Instant} at which the authenticating token
   * expires, so a long-lived response (the console stream) can end when its token does.
   */
  public static final String EXPIRES_AT = JwtAuthFilter.class.getName() + ".EXPIRES_AT";

  private final JwtService jwtService;

  /**
   * The stateless chain reloads the context from here when an async response (the console stream)
   * completes and the request is dispatched again. This filter does not run on that dispatch, and
   * the token may have expired by then, so without it the completion would be refused.
   */
  private final SecurityContextRepository contextRepository =
      new RequestAttributeSecurityContextRepository();

  public JwtAuthFilter(JwtService jwtService) {
    this.jwtService = jwtService;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return;
    }

    String token = authHeader.substring(7);
    // Only establish authentication when the token is valid. Do NOT short-circuit with a 401 on an
    // invalid token — that would reject even permitAll/public endpoints (e.g. public file serving)
    // whenever a stale/garbage Authorization header is present. Leaving the context unauthenticated
    // lets Spring Security's authorization rules decide: public paths proceed, protected paths 401.
    if (jwtService.isTokenValid(token)) {
      String userId = jwtService.extractUserId(token);
      String role = jwtService.extractRole(token);

      UsernamePasswordAuthenticationToken auth =
          new UsernamePasswordAuthenticationToken(
              userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
      auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(auth);
      contextRepository.saveContext(SecurityContextHolder.getContext(), request, response);
      request.setAttribute(EXPIRES_AT, jwtService.extractExpiration(token));
    }

    filterChain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return request.getServletPath().equals("/auth/login");
  }
}
