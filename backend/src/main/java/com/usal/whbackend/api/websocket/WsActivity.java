package com.usal.whbackend.api.websocket;

import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.telemetry.TelemetryPort;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Called by the WebSocket handlers and handshake interceptors. Feeds the console event buffer and
 * the {@code ws.*} metrics. Feed names are the bounded literals below (the user-orders template is
 * never the real user id).
 */
@Component
public class WsActivity {

  public static final String ORDERS = "/ws/v1/orders";
  public static final String USER_ORDERS = "/ws/v1/orders/{userId}";
  public static final String VEHICLES = "/ws/v1/vehicles";
  public static final String STOCK_ALERTS = "/ws/v1/stock/alerts";

  /**
   * Feeds contain slashes, so console URLs and stream subscriptions name them by a short slug:
   * {@code orders}, {@code user-orders}, {@code vehicles}, {@code stock-alerts}.
   */
  public static final Map<String, String> FEEDS_BY_SLUG =
      Map.of(
          "orders", ORDERS,
          "user-orders", USER_ORDERS,
          "vehicles", VEHICLES,
          "stock-alerts", STOCK_ALERTS);

  private static final int MAX_DETAIL = 120;

  private final WsEventBuffer buffer;
  private final TelemetryPort telemetry;
  private final Map<String, AtomicInteger> open = new ConcurrentHashMap<>();

  public WsActivity(WsEventBuffer buffer, TelemetryPort telemetry) {
    this.buffer = buffer;
    this.telemetry = telemetry;
  }

  public void connected(String feed, String detail) {
    open.computeIfAbsent(feed, f -> new AtomicInteger()).incrementAndGet();
    telemetry.recordWsSession(feed, 1);
    buffer.record(feed, event("connect", detail));
  }

  public void closed(String feed, String detail) {
    AtomicInteger count = open.computeIfAbsent(feed, f -> new AtomicInteger());
    boolean wasOpen = count.getAndUpdate(n -> n > 0 ? n - 1 : 0) > 0;
    if (wasOpen) {
      telemetry.recordWsSession(feed, -1);
    }
    telemetry.recordWsMessage(feed, "closed");
    buffer.record(feed, event("close", detail));
  }

  public void pushed(String feed, String detail) {
    telemetry.recordWsMessage(feed, "pushed");
    buffer.record(feed, event("push", detail));
  }

  public void rejected(String feed, String detail) {
    telemetry.recordWsMessage(feed, "rejected");
    buffer.record(feed, event("reject", detail));
  }

  private static WsEvent event(String kind, String detail) {
    return new WsEvent(Instant.now(), kind, sanitize(detail));
  }

  /** Drops any query string and token value, then caps the length. */
  private static String sanitize(String detail) {
    if (detail == null) {
      return "";
    }
    String clean = detail.replaceAll("\\?\\S*", "").replaceAll("token=\\S*", "token=[redacted]");
    return clean.length() > MAX_DETAIL ? clean.substring(0, MAX_DETAIL) : clean;
  }
}
