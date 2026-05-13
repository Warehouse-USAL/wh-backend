package com.usal.whbackend.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.api.order.OrderResponse;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.service.OrderEventPublisher;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class OrderWebSocketHandler extends TextWebSocketHandler implements OrderEventPublisher {

  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .setPropertyNamingStrategy(
              com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
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
    broadcast(buildOrderEvent(order));
  }

  protected void broadcast(String json) {
    for (WebSocketSession session : sessions.values()) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(json));
        } catch (IOException ignored) {}
      }
    }
  }

  protected String buildOrderEvent(Order order) {
    try {
      return objectMapper.writeValueAsString(
          Map.of("event", "order.updated", "payload", OrderResponse.from(order)));
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {}
}
