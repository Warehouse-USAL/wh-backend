package com.usal.whbackend.api.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    OrderWebSocketHandler handler = new OrderWebSocketHandler(mock(WsActivity.class));

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
    OrderWebSocketHandler handler = new OrderWebSocketHandler(mock(WsActivity.class));

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
    OrderWebSocketHandler handler = new OrderWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  // ── UserOrderWebSocketHandler ──────────────────────────────────────────────

  @Test
  void userOrderHandler_broadcastsOnlyToMatchingUser() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

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
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = sessionWithUri("usr-99", "/ws/v1/orders/usr-1");
    handler.afterConnectionEstablished(session);

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session).close(any(CloseStatus.class));
    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  // ── VehicleWebSocketHandler ────────────────────────────────────────────────

  @Test
  void vehicleHandler_broadcastsVehicleUpdate() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler(mock(WsActivity.class));

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
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);

    handler.broadcastVehicleError("v-1", "CONNECTION_LOST", "Sin señal", "2026-05-01T10:00:00Z");

    verify(session).sendMessage(any(TextMessage.class));
  }

  // ── StockAlertWebSocketHandler ─────────────────────────────────────────────

  @Test
  void stockHandler_broadcastsStockAlert() throws IOException {
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler(mock(WsActivity.class));

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
    OrderWebSocketHandler handler = new OrderWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);
    handler.handleTransportError(session, new IllegalStateException("boom"));

    handler.broadcastOrderUpdate(orderWithStatus(OrderStatus.PENDING));

    verify(session, never()).sendMessage(any(TextMessage.class));
  }

  @Test
  void orderHandler_sendFailureIsSwallowed() throws IOException {
    OrderWebSocketHandler handler = new OrderWebSocketHandler(mock(WsActivity.class));

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
    TestableOrderHandler() {
      super(mock(WsActivity.class));
    }

    void receive(WebSocketSession session, TextMessage message) {
      handleTextMessage(session, message);
    }
  }

  @Test
  void userOrderHandler_sessionRemovedOnCloseAndTransportError() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

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
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

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
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

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
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getAttributes()).thenReturn(Map.of("userId", "usr-1"));
    when(session.getUri()).thenReturn(null);

    handler.afterConnectionEstablished(session);

    verify(session).close(any(CloseStatus.class));
  }

  @Test
  void userOrderHandler_closeFailureOnRejectionIsSwallowed() throws IOException {
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(mock(WsActivity.class));

    WebSocketSession session = sessionWithUri("usr-99", "/ws/v1/orders/usr-1");
    doThrow(new IOException("already gone")).when(session).close(any(CloseStatus.class));

    handler.afterConnectionEstablished(session);

    verify(session).close(any(CloseStatus.class));
  }

  @Test
  void vehicleHandler_sessionRemovedOnCloseAndTransportError() throws IOException {
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler(mock(WsActivity.class));

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
    VehicleWebSocketHandler handler = new VehicleWebSocketHandler(mock(WsActivity.class));

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
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler(mock(WsActivity.class));

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
    StockAlertWebSocketHandler handler = new StockAlertWebSocketHandler(mock(WsActivity.class));

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

  // ── console activity ───────────────────────────────────────────────────────

  @Test
  void handlersReportConnectPushAndCloseToWsActivity() throws IOException {
    WsActivity activity = mock(WsActivity.class);
    OrderWebSocketHandler handler = new OrderWebSocketHandler(activity);
    WebSocketSession session = openSession("s1");
    handler.afterConnectionEstablished(session);
    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setId("o_1");
    handler.broadcastOrderUpdate(order);
    handler.afterConnectionClosed(session, CloseStatus.NORMAL);
    handler.handleTransportError(session, new IllegalStateException("late"));

    verify(activity).connected(WsActivity.ORDERS, "session opened");
    verify(activity).pushed(WsActivity.ORDERS, "order.updated o_1 status PENDING");
    verify(activity, times(1)).closed(WsActivity.ORDERS, "session closed");
  }

  @Test
  void vehicleAndStockHandlersReportPushSummaries() throws IOException {
    WsActivity activity = mock(WsActivity.class);
    VehicleWebSocketHandler vehicles = new VehicleWebSocketHandler(activity);
    vehicles.afterConnectionEstablished(openSession("v"));
    vehicles.broadcastVehicleError("veh-1", "LOST", "m", "2026-01-01T00:00:00Z");
    verify(activity).connected(WsActivity.VEHICLES, "session opened");
    verify(activity).pushed(WsActivity.VEHICLES, "vehicle.error veh-1 LOST");

    StockAlertWebSocketHandler stock = new StockAlertWebSocketHandler(activity);
    stock.afterConnectionEstablished(openSession("s"));
    com.usal.whbackend.domain.Product product = new com.usal.whbackend.domain.Product();
    product.setId("p-1");
    product.setSku("SKU-1");
    product.setName("n");
    product.setMinimumStock(10);
    stock.broadcastStockAlert(product, 3);
    verify(activity).pushed(WsActivity.STOCK_ALERTS, "stock.alert SKU-1 stock 3");
  }

  @Test
  void userOrderHandlerReportsRejectionPushAndNeverCountsRejectedSessionClose() throws IOException {
    WsActivity activity = mock(WsActivity.class);
    UserOrderWebSocketHandler handler = new UserOrderWebSocketHandler(activity);

    WebSocketSession bad = sessionWithUri("usr-99", "/ws/v1/orders/usr-1");
    handler.afterConnectionEstablished(bad);
    handler.afterConnectionClosed(bad, CloseStatus.NOT_ACCEPTABLE);
    verify(activity).rejected(eq(WsActivity.USER_ORDERS), anyString());
    verify(activity, never()).connected(anyString(), anyString());
    verify(activity, never()).closed(anyString(), anyString());

    WebSocketSession ok = sessionWithUri("usr-1", "/ws/v1/orders/usr-1");
    handler.afterConnectionEstablished(ok);
    Order order = orderWithStatus(OrderStatus.PENDING);
    order.setId("o_2");
    order.setRequestedByUserId("usr-1");
    handler.broadcastOrderUpdate(order);
    verify(activity).connected(WsActivity.USER_ORDERS, "session opened");
    verify(activity).pushed(WsActivity.USER_ORDERS, "order.updated o_2 status PENDING");
  }
}
