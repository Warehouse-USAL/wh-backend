package com.usal.whbackend.api.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class WebSocketHandlersTest {

  // ── OrderWebSocketHandler ──────────────────────────────────────────────────

  @Test
  void orderHandler_broadcastsToAllSessions() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();

    WebSocketSession s1 = openSession("s1");
    WebSocketSession s2 = openSession("s2");
    handler.afterConnectionEstablished(s1);
    handler.afterConnectionEstablished(s2);

    Order order = orderWithStatus(OrderStatus.PENDING);
    handler.broadcastOrderUpdate(order);

    verify(s1).sendMessage(any(TextMessage.class));
    verify(s2).sendMessage(any(TextMessage.class));
  }

  @Test
  void orderHandler_closedSessionSkippedOnBroadcast() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();

    WebSocketSession open = openSession("open");
    WebSocketSession closed = closedSession("closed");
    handler.afterConnectionEstablished(open);
    handler.afterConnectionEstablished(closed);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(open).sendMessage(any(TextMessage.class));
    verify(closed, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void orderHandler_sessionRemovedOnClose() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  // ── UserOrderWebSocketHandler ──────────────────────────────────────────────

  @Test
  void userOrderHandler_broadcastsOnlyToMatchingUser() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession owner = sessionWithUri("usr-1", "/ws/v1/orders/usr-1");
    WebSocketSession other = sessionWithUri("usr-2", "/ws/v1/orders/usr-2");
    handler.afterConnectionEstablished(owner);
    handler.afterConnectionEstablished(other);

    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setRequestedByUserId("usr-1");
    handler.broadcastOrderUpdate(order);

    verify(owner).sendMessage(any(TextMessage.class));
    verify(other, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void userOrderHandler_rejectsSessionIfUserIdMismatch() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession session = sessionWithUri("usr-99", "/ws/v1/orders/usr-1");
    handler.afterConnectionEstablished(session);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session).close(any(CloseStatus.class));
    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  // ── VehicleWebSocketHandler ────────────────────────────────────────────────

  @Test
  void vehicleHandler_broadcastsVehicleUpdate() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler();

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);

    Vehicle vehicle = new Vehicle();
    vehicle.setId("v-1");
    vehicle.setStatus(VehicleStatus.IDLE);
    handler.broadcastVehicleUpdate(vehicle);

    verify(session).sendMessage(any(TextMessage.class));
  }

  @Test
  void vehicleHandler_broadcastsVehicleError() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler();

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);

    handler.broadcastVehicleError("v-1", "CONNECTION_LOST", "Sin señal", "2026-05-01T10:00:00Z");

    verify(session).sendMessage(any(TextMessage.class));
  }

  // ── StockAlertWebSocketHandler ─────────────────────────────────────────────

  @Test
  void stockHandler_broadcastsStockAlert() throws IOException {
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler();

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);

    com.usal.whbackend.domain.Product product = new com.usal.whbackend.domain.Product();
    product.setId("p-1");
    product.setSku("SKU-001");
    product.setName("Test Product");
    product.setMinimumStock(10);
    handler.broadcastStockAlert(product, 3);

    verify(session).sendMessage(any(TextMessage.class));
  }

  // ── helpers ────────────────────────────────────────────────────────────────

  private WebSocketSession openSession(String id) {
    WebSocketSession s = mock(WebSocketSession.class);
    when(s.getId()).thenReturn(id);
    when(s.isOpen()).thenReturn(true);
    when(s.getAttributes()).thenReturn(Map.of("userId", id));
    try {
      when(s.getUri()).thenReturn(URI.create("/ws/v1/orders"));
    } catch (Exception ignored) {
    }
    return s;
  }

  private WebSocketSession closedSession(String id) {
    WebSocketSession s = mock(WebSocketSession.class);
    when(s.getId()).thenReturn(id);
    when(s.isOpen()).thenReturn(false);
    return s;
  }

  private WebSocketSession sessionWithUri(String userId, String uri) {
    WebSocketSession s = mock(WebSocketSession.class);
    when(s.getId()).thenReturn(userId);
    when(s.isOpen()).thenReturn(true);
    Map<String, Object> attrs = new java.util.HashMap<>();
    attrs.put("userId", userId);
    when(s.getAttributes()).thenReturn(attrs);
    try {
      when(s.getUri()).thenReturn(URI.create(uri));
    } catch (Exception ignored) {
    }
    return s;
  }

  private Order orderWithStatus(OrderStatus status) {
    Order o = new Order();
    o.setStatus(status);
    return o;
  }

  // ── transport errors / message handling ────────────────────────────────────

  @Test
  void orderHandler_sessionRemovedOnTransportError() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);
    handler.handleTransportError(session, new IllegalStateException("boom"));

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void orderHandler_sendFailureIsSwallowed() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler();

    WebSocketSession failing = openSession("s1");
    doThrow(new IOException("closed")).when(failing).sendMessage(any(TextMessage.class));
    handler.afterConnectionEstablished(failing);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(failing).sendMessage(any(TextMessage.class));
  }

  @Test
  void orderHandler_incomingTextMessagesAreIgnored() {
    OrderWebSocketHandler handler = new TestableOrderHandler();
    ((TestableOrderHandler) handler).receive(openSession("s1"), new TextMessage("ping"));
  }

  private static class TestableOrderHandler extends OrderWebSocketHandler {
    void receive(WebSocketSession session, TextMessage message) {
      handleTextMessage(session, message);
    }
  }

  @Test
  void userOrderHandler_sessionRemovedOnCloseAndTransportError() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession a = sessionWithUri("usr-1", "/ws/v1/orders/usr-1");
    WebSocketSession b = sessionWithUri("usr-2", "/ws/v1/orders/usr-2");
    handler.afterConnectionEstablished(a);
    handler.afterConnectionEstablished(b);
    handler.afterConnectionClosed(a, CloseStatus.NORMAL);
    handler.handleTransportError(b, new IllegalStateException("boom"));

    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setRequestedByUserId("usr-1");
    handler.broadcastOrderUpdate(order);

    verify(a, never()).sendMessage(any(TextMessage.class));
    verify(b, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void userOrderHandler_sendFailureIsSwallowed() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession owner = sessionWithUri("usr-1", "/ws/v1/orders/usr-1");
    doThrow(new IOException("closed")).when(owner).sendMessage(any(TextMessage.class));
    handler.afterConnectionEstablished(owner);

    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setRequestedByUserId("usr-1");
    handler.broadcastOrderUpdate(order);

    verify(owner).sendMessage(any(TextMessage.class));
  }

  @Test
  void userOrderHandler_closedSessionIsSkipped() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession owner = sessionWithUri("usr-1", "/ws/v1/orders/usr-1");
    handler.afterConnectionEstablished(owner);
    when(owner.isOpen()).thenReturn(false);

    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setRequestedByUserId("usr-1");
    handler.broadcastOrderUpdate(order);

    verify(owner, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void userOrderHandler_sessionWithoutUriIsRejected() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(Map.of("userId", "usr-1"));
    when(session.getUri()).thenReturn(null);

    handler.afterConnectionEstablished(session);

    verify(session).close(any(CloseStatus.class));
  }

  @Test
  void userOrderHandler_closeFailureOnRejectionIsSwallowed() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler();

    WebSocketSession session = sessionWithUri("usr-99", "/ws/v1/orders/usr-1");
    doThrow(new IOException("already gone")).when(session).close(any(CloseStatus.class));

    handler.afterConnectionEstablished(session);

    verify(session).close(any(CloseStatus.class));
  }

  @Test
  void vehicleHandler_sessionRemovedOnCloseAndTransportError() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler();

    WebSocketSession a = openSession("s1");
    WebSocketSession b = openSession("s2");
    handler.afterConnectionEstablished(a);
    handler.afterConnectionEstablished(b);
    handler.afterConnectionClosed(a, CloseStatus.NORMAL);
    handler.handleTransportError(b, new IllegalStateException("boom"));

    handler.broadcastVehicleError("v-1", "X", "y", "2026-05-01T10:00:00Z");

    verify(a, never()).sendMessage(any(TextMessage.class));
    verify(b, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void vehicleHandler_sendFailureIsSwallowedAndClosedSessionSkipped() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler();

    WebSocketSession failing = openSession("s1");
    doThrow(new IOException("closed")).when(failing).sendMessage(any(TextMessage.class));
    WebSocketSession closed = closedSession("s2");
    handler.afterConnectionEstablished(failing);
    handler.afterConnectionEstablished(closed);

    handler.broadcastVehicleError("v-1", "X", "y", "2026-05-01T10:00:00Z");

    verify(failing).sendMessage(any(TextMessage.class));
    verify(closed, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void stockHandler_sessionRemovedOnCloseAndTransportError() throws IOException {
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler();

    WebSocketSession a = openSession("s1");
    WebSocketSession b = openSession("s2");
    handler.afterConnectionEstablished(a);
    handler.afterConnectionEstablished(b);
    handler.afterConnectionClosed(a, CloseStatus.NORMAL);
    handler.handleTransportError(b, new IllegalStateException("boom"));

    handler.broadcastStockAlert(product(), 1);

    verify(a, never()).sendMessage(any(TextMessage.class));
    verify(b, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void stockHandler_sendFailureIsSwallowedAndClosedSessionSkipped() throws IOException {
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler();

    WebSocketSession failing = openSession("s1");
    doThrow(new IOException("closed")).when(failing).sendMessage(any(TextMessage.class));
    WebSocketSession closed = closedSession("s2");
    handler.afterConnectionEstablished(failing);
    handler.afterConnectionEstablished(closed);

    handler.broadcastStockAlert(product(), 1);

    verify(failing).sendMessage(any(TextMessage.class));
    verify(closed, never()).sendMessage(any(TextMessage.class));
  }

  private com.usal.whbackend.domain.Product product() {
    com.usal.whbackend.domain.Product p = new com.usal.whbackend.domain.Product();
    p.setId("p-1");
    p.setSku("SKU-001");
    p.setName("Test Product");
    p.setMinimumStock(10);
    return p;
  }
}
