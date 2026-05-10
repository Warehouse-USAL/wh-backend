package com.usal.whbackend.api.websocket;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WebSocketHandlersTest {

  private final WebSocketSession session = mock(WebSocketSession.class);
  private final TextMessage message = new TextMessage("test");

  @Test
  void orderHandler_throwsUnsupported() throws Exception {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();
    assertThrows(
        UnsupportedOperationException.class, () -> handler.handleTextMessage(session, message));
  }

  @Test
  void vehicleHandler_throwsUnsupported() throws Exception {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler();
    assertThrows(
        UnsupportedOperationException.class, () -> handler.handleTextMessage(session, message));
  }

  @Test
  void stockAlertHandler_throwsUnsupported() throws Exception {
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler();
    assertThrows(
        UnsupportedOperationException.class, () -> handler.handleTextMessage(session, message));
  }

  @Test
  void userOrderHandler_throwsUnsupported() throws Exception {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();
    assertThrows(
        UnsupportedOperationException.class, () -> handler.handleTextMessage(session, message));
  }
}
