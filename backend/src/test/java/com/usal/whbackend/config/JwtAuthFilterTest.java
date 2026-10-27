package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

  @Mock JwtService jwtService;
  @InjectMocks JwtAuthFilter filter;

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void validToken_setsSecurityContextAndContinuesChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer validtoken");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    when(jwtService.isTokenValid("validtoken")).thenReturn(true);
    when(jwtService.extractUserId("validtoken")).thenReturn("user-uuid-123");
    when(jwtService.extractRole("validtoken")).thenReturn("ADMIN_SYSTEM");

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    assertThat(SecurityContextHolder.getContext().getAuthentication().getName())
        .isEqualTo("user-uuid-123");
    assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
        .extracting("authority")
        .containsExactly("ROLE_ADMIN_SYSTEM");
    verify(chain).doFilter(request, response);
  }

  @Test
  void invalidToken_doesNotAuthenticateAndContinuesChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer badtoken");
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    when(jwtService.isTokenValid("badtoken")).thenReturn(false);

    filter.doFilter(request, response, chain);

    // An invalid token must NOT short-circuit with 401 — that would reject even
    // permitAll/public endpoints when a stale/garbage header is present. The filter
    // leaves the context unauthenticated and continues; Spring Security authorization
    // then decides (public paths proceed, protected paths 401 via the entry point).
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    assertThat(response.getStatus()).isEqualTo(200);
    verify(chain).doFilter(request, response);
  }

  @Test
  void noAuthHeader_continuesChainWithoutAuthentication() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    filter.doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(request, response);
  }

  @Test
  void validToken_exposesItsExpiryAsARequestAttribute() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer validtoken");
    java.time.Instant expiry = java.time.Instant.parse("2026-10-18T12:00:00Z");
    when(jwtService.isTokenValid("validtoken")).thenReturn(true);
    when(jwtService.extractUserId("validtoken")).thenReturn("u1");
    when(jwtService.extractRole("validtoken")).thenReturn("SUPERADMIN");
    when(jwtService.extractExpiration("validtoken")).thenReturn(expiry);

    filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

    assertThat(request.getAttribute(JwtAuthFilter.EXPIRES_AT)).isEqualTo(expiry);
    // Saved for the async re-dispatch, which this filter does not run on.
    assertThat(
            request.getAttribute(
                org.springframework.security.web.context.RequestAttributeSecurityContextRepository
                    .DEFAULT_REQUEST_ATTR_NAME))
        .isNotNull();
  }

  @Test
  void tokenOfADeactivatedUser_doesNotAuthenticateAndContinuesChain() throws Exception {
    JwtService real = new JwtService("test-secret-key-for-unit-tests-min32ch", 86_400_000L);
    UserActivityCache activity = mock(UserActivityCache.class);
    real.setUserActivity(activity);
    com.usal.whbackend.domain.User user = new com.usal.whbackend.domain.User();
    user.setId("USR-9");
    user.setRole(com.usal.whbackend.domain.UserRole.OPERATOR);
    when(activity.isActive("USR-9")).thenReturn(false);
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer " + real.generateToken(user));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain chain = mock(FilterChain.class);

    new JwtAuthFilter(real).doFilter(request, response, chain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(chain).doFilter(request, response);
  }
}
