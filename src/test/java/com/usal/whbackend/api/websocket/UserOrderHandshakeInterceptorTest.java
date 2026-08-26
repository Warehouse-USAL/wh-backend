package com.usal.whbackend.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.config.JwtService;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
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
class UserOrderHandshakeInterceptorTest {

  @Mock JwtService jwtService;
  @Mock WebSocketHandler handler;
  @Mock ServerHttpRequest request;
  @Mock ServerHttpResponse response;

  UserOrderHandshakeInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor = new UserOrderHandshakeInterceptor(jwtService);
  }

  @Test
  void beforeHandshake_matchingUserId_returnsTrue() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1?token=valid-token"));
    when(jwtService.isTokenValid("valid-token")).thenReturn(true);
    when(jwtService.extractUserId("valid-token")).thenReturn("usr-1");

    Map<String, Object> attrs = new HashMap<>();
    assertTrue(interceptor.beforeHandshake(request, response, handler, attrs));
    assertEquals("usr-1", attrs.get("userId"));
  }

  @Test
  void beforeHandshake_mismatchedUserId_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-2?token=valid-token"));
    when(jwtService.isTokenValid("valid-token")).thenReturn(true);
    when(jwtService.extractUserId("valid-token")).thenReturn("usr-1");

    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_invalidToken_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1?token=bad-token"));
    when(jwtService.isTokenValid("bad-token")).thenReturn(false);
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_missingToken_returnsFalse() throws Exception {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1"));
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, handler, attrs));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_queryWithoutTokenParam_returnsFalse() {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1?foo=bar&baz=qux"));

    assertFalse(interceptor.beforeHandshake(request, response, handler, new HashMap<>()));
    verify(response).setStatusCode(HttpStatus.FORBIDDEN);
  }

  @Test
  void beforeHandshake_tokenAfterOtherParams_isFound() {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1?foo=bar&token=t"));
    when(jwtService.isTokenValid("t")).thenReturn(true);
    when(jwtService.extractUserId("t")).thenReturn("usr-1");

    assertTrue(interceptor.beforeHandshake(request, response, handler, new HashMap<>()));
  }

  @Test
  void beforeHandshake_trailingSlashPath_stillExtractsUserId() {
    when(request.getURI()).thenReturn(URI.create("/ws/v1/orders/usr-1/?token=t"));
    when(jwtService.isTokenValid("t")).thenReturn(true);
    when(jwtService.extractUserId("t")).thenReturn("usr-1");

    assertTrue(interceptor.beforeHandshake(request, response, handler, new HashMap<>()));
  }

  @Test
  void afterHandshake_isANoOp() {
    assertDoesNotThrow(() -> interceptor.afterHandshake(request, response, handler, null));
  }
}
