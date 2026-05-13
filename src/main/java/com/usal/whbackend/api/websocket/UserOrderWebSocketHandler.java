package com.usal.whbackend.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.api.order.OrderResponse;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderEventPublisher;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class UserOrderWebSocketHandler extends TextWebSocketHandler implements OrderEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(UserOrderWebSocketHandler.class);
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .setPropertyNamingStrategy(
              com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    String jwtUserId = (String) session.getAttributes().get("userId");
    String pathUserId = extractUserIdFromPath(session);

    if (pathUserId == null || !pathUserId.equals(jwtUserId)) {
      try {
        session.close(CloseStatus.NOT_ACCEPTABLE);
      } catch (IOException e) {
        log.warn("Failed to close unauthorized WebSocket session: {}", e.getMessage());
      }
      return;
    }
    sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 1000, 65536));
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    sessions.remove(session.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    sessions.remove(session.getId());
  }

  @Override
  public void broadcastOrderUpdate(Order order) {
    String json = buildOrderEvent(order);
    for (WebSocketSession session : sessions.values()) {
      if (session.isOpen()) {
        String sessionUserId = (String) session.getAttributes().get("userId");
        if (sessionUserId != null && sessionUserId.equals(order.getRequestedByUserId())) {
          try {
            session.sendMessage(new TextMessage(json));
          } catch (IOException ignored) {}
        }
      }
    }
  }

  private String buildOrderEvent(Order order) {
    try {
      return objectMapper.writeValueAsString(Map.of("event", "order.updated", "payload", OrderResponse.from(order)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private String extractUserIdFromPath(WebSocketSession session) {
    if (session.getUri() == null) return null;
    String path = session.getUri().getPath();
    String[] segments = path.split("/");
    return segments.length > 0 ? segments[segments.length - 1] : null;
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {}
}
