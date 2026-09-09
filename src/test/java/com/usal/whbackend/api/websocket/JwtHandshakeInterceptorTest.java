package com.usal.whbackend.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.config.JwtService;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

@ExtendWith(MockitoExtension.class)
class JwtHandshakeInterceptorTest {

  @Mock JwtService jwtService;
  @Mock WebSocketHandler handler;
  @Mock ServerHttpRequest request;
  @Mock ServerHttpResponse response;

  JwtHandshakeInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new JwtHandshakeInterceptor(jwtService, Set.of("ADMIN_SYSTEM", "ADMIN_WAREHOUSE"));
  }

  @Test
  void beforeHandshake_validTokenAllowedRole_returnsTrue() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles?token=valid-token"));
    when(jwtService.isTokenValid("valid-token")).thenReturn(true);
    when(jwtService.extractRole("valid-token")).thenReturn("ADMIN_WAREHOUSE");
    when(jwtService.extractUserId("valid-token")).thenReturn("usr-1");

    Map<String, Object> attrs = new HashMap<>();
    assertTrue(interceptor.beforeHandshake(request, response, handler, attrs));
    assertEquals("usr-1", attrs.get("userId"));
    assertEquals("ADMIN_WAREHOUSE", attrs.get("role"));
  }

  @Test
  void beforeHandshake_missingToken_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles"));
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_invalidToken_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles?token=bad-token"));
    when(jwtService.isTokenValid("bad-token")).thenReturn(false);
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_disallowedRole_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles?token=sales-token"));
    when(jwtService.isTokenValid("sales-token")).thenReturn(true);
    when(jwtService.extractRole("sales-token")).thenReturn("ADMIN_SALES");
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_tokenAmongMultipleParams_works() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles?foo=bar&token=valid-token"));
    when(jwtService.isTokenValid("valid-token")).thenReturn(true);
    when(jwtService.extractRole("valid-token")).thenReturn("ADMIN_WAREHOUSE");
    when(jwtService.extractUserId("valid-token")).thenReturn("usr-1");

    Map<String, Object> attrs = new HashMap<>();
    assertTrue(interceptor.beforeHandshake(request, response, handler, attrs));
  }

  @Test
  void beforeHandshake_queryWithoutTokenParam_returnsFalse() {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/vehicles?foo=bar"));

    assertFalse(interceptor.beforeHandshake(request, response, handler, new HashMap<>()));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void afterHandshake_isANoOp() {
    assertDoesNotThrow(() -> interceptor.afterHandshake(request, response, handler, null));
  }
}
