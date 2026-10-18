package com.usal.whbackend.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.service.StockEventPublisher;
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
public class StockAlertWebSocketHandler extends TextWebSocketHandler
    implements StockEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(StockAlertWebSocketHandler.class);
  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final WsActivity wsActivity;
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
          .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .setPropertyNamingStrategy(
              com.fasterxml.jackson.databind.PropertyNamingStrategies.SNAKE_CASE);

  public StockAlertWebSocketHandler(WsActivity wsActivity) {
    this.wsActivity = wsActivity;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) {
    sessions.put(session.getId(), new ConcurrentWebSocketSessionDecorator(session, 1000, 65536));
    wsActivity.connected(WsActivity.STOCK_ALERTS, "session opened");
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    dropSession(session);
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) {
    dropSession(session);
  }

  private void dropSession(WebSocketSession session) {
    if (sessions.remove(session.getId()) != null) {
      wsActivity.closed(WsActivity.STOCK_ALERTS, "session closed");
    }
  }

  @Override
  public void broadcastStockAlert(Product product, int currentStock) {
    Map<String, Object> payload =
        Map.of(
            "product_id", product.getId(),
            "sku", product.getSku(),
            "name", product.getName(),
            "current_stock", currentStock,
            "minimum_stock", product.getMinimumStock());
    broadcast(
        serialize(Map.of("event", "stock.alert", "payload", payload)),
        "stock.alert " + product.getSku() + " stock " + currentStock);
  }

  private void broadcast(String json, String summary) {
    for (WebSocketSession session : sessions.values()) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(json));
          wsActivity.pushed(WsActivity.STOCK_ALERTS, summary);
        } catch (IOException e) {
          log.warn("Failed to send stock alert to session {}: {}", session.getId(), e.getMessage());
        }
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
