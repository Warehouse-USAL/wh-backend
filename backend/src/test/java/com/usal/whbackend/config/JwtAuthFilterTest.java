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
}
