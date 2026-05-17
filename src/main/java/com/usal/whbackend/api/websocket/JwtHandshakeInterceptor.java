package com.usal.whbackend.api.websocket;

import com.usal.whbackend.config.JwtService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class JwtHandshakeInterceptor implements HandshakeInterceptor {

  private final JwtService jwtService;
  private final Set<String> allowedRoles;

  public JwtHandshakeInterceptor(JwtService jwtService, Set<String> allowedRoles) {
    this.jwtService = jwtService;
    this.allowedRoles = Set.copyOf(allowedRoles);
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    String token = extractToken(request);
    if (token == null || !jwtService.isTokenValid(token)) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    String role = jwtService.extractRole(token);
    if (!allowedRoles.contains(role)) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    attributes.put("userId", jwtService.extractUserId(token));
    attributes.put("role", role);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}

  private String extractToken(ServerHttpRequest request) {
    String query = request.getURI().getQuery();
    if (query == null) return null;
    for (String param : query.split("&")) {
      if (param.startsWith("token=")) {
        return URLDecoder.decode(param.substring(6), StandardCharsets.UTF_8);
      }
    }
    return null;
  }
}
