package com.usal.whbackend.api.console;

import com.usal.whbackend.api.websocket.WsActivity;
import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Recent WebSocket activity for the console. Feeds contain slashes, so the path variable is a short
 * slug mapped to the feed string used by the buffer and the {@code ws.*} metrics: {@code orders} =
 * {@code /ws/v1/orders}, {@code user-orders} = {@code /ws/v1/orders/{userId}}, {@code vehicles} =
 * {@code /ws/v1/vehicles}, {@code stock-alerts} = {@code /ws/v1/stock/alerts}.
 */
@RestController
@RequestMapping("/console/ws-events")
@Tag(name = "Console", description = "Backend console (SUPERADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('SUPERADMIN')")
public class WsEventsController {

  private static final Map<String, String> FEEDS =
      Map.of(
          "orders", WsActivity.ORDERS,
          "user-orders", WsActivity.USER_ORDERS,
          "vehicles", WsActivity.VEHICLES,
          "stock-alerts", WsActivity.STOCK_ALERTS);

  private final WsEventBuffer buffer;

  public WsEventsController(WsEventBuffer buffer) {
    this.buffer = buffer;
  }

  @Operation(
      summary = "Recent WebSocket events of a feed",
      description =
          "Newest first, at most 200. Feed is one of orders, user-orders, vehicles, stock-alerts.")
  @GetMapping("/{feed}")
  public ResponseEntity<Map<String, Object>> events(@PathVariable String feed) {
    String key = FEEDS.get(feed);
    if (key == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "UNKNOWN_FEED");
    }
    List<WsEvent> events = buffer.recent(key);
    return ResponseEntity.ok(Map.of("feed", key, "events", events));
  }
}
