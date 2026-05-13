package com.usal.whbackend.api.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.api.vehicle.VehicleResponse;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.service.VehicleEventPublisher;
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
public class VehicleWebSocketHandler extends TextWebSocketHandler implements VehicleEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(VehicleWebSocketHandler.class);
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
  public void broadcastVehicleUpdate(Vehicle vehicle) {
    broadcast(serialize(Map.of("event", "vehicle.updated", "payload", VehicleResponse.from(vehicle))));
  }

  @Override
  public void broadcastVehicleError(
      String vehicleId, String errorCode, String message, String lastSeenAt) {
    Map<String, Object> payload =
        Map.of(
            "id", vehicleId,
            "error_code", errorCode,
            "message", message,
            "last_seen_at", lastSeenAt);
    broadcast(serialize(Map.of("event", "vehicle.error", "payload", payload)));
  }

  private void broadcast(String json) {
    for (WebSocketSession session : sessions.values()) {
      if (session.isOpen()) {
        try {
          session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
          log.warn("Failed to send vehicle event to session {}: {}", session.getId(), e.getMessage());
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
