package com.usal.whbackend.api.websocket;

import com.usal.whbackend.config.JwtService;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

public class UserOrderHandshakeInterceptor implements HandshakeInterceptor {

  private final JwtService jwtService;

  public UserOrderHandshakeInterceptor(JwtService jwtService) {
    this.jwtService = jwtService;
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
    String tokenUserId = jwtService.extractUserId(token);
    String pathUserId = extractUserIdFromPath(request.getURI().getPath());
    if (!tokenUserId.equals(pathUserId)) {
      response.setStatusCode(HttpStatus.FORBIDDEN);
      return false;
    }
    attributes.put("userId", tokenUserId);
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

  private String extractUserIdFromPath(String path) {
    // path is like /ws/v1/orders/USR-001
    String normalizedPath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String[] parts = normalizedPath.split("/");
    return parts[parts.length - 1];
  }
}
