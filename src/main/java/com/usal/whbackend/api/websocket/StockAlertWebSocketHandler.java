package com.usal.whbackend.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.StockEventPublisher;
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
public class StockAlertWebSocketHandler extends TextWebSocketHandler implements StockEventPublisher {

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
  public void broadcastStockAlert(Product product) {
    Map<String, Object> payload =
        Map.of(
            "product_id", product.getId(),
            "sku", product.getSku(),
            "name", product.getName(),
            "current_stock", product.getAvailableStock(),
            "minimum_stock", product.getMinimumStock());
    broadcast(serialize(Map.of("event", "stock.alert", "payload", payload)));
  }

  private void broadcast(String json) {
    for (WebSocketSession session : sessions.values()) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(json));
        } catch (IOException ignored) {}
      }
    }
  }

  private String serialize(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) {}
}
