package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import java.util.List;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * One WebSocket feed, event-driven: no timer. The snapshot is the buffer (newest first, as {@code
 * GET /console/ws-events/{feed}}); every event recorded afterwards is pushed as it happens. The
 * snapshot is taken under the buffer's feed lock, the lock listeners run under, so no event is
 * missed or sent twice between the two.
 */
final class WsEventsSource extends Source {

  /** Same fields, in the same order, as the REST response. */
  record Page(String feed, List<WsEvent> events) {}

  private final String feed;
  private final WsEventBuffer buffer;
  private final JsonMapper json;
  private final Consumer<WsEvent> listener = this::onEvent;

  WsEventsSource(SourceKey.WsEvents key, WsEventBuffer buffer, JsonMapper json) {
    this.feed = key.feed();
    this.buffer = buffer;
    this.json = json;
  }

  @Override
  void start() {
    buffer.addListener(feed, listener);
  }

  @Override
  void stop() {
    buffer.removeListener(feed, listener);
  }

  @Override
  void join(Subscriber subscriber) {
    buffer.withRecent(
        feed,
        events -> {
          subscriber.data(false, json.writeValueAsString(new Page(feed, events)));
          subscribers.add(subscriber);
        });
  }

  private void onEvent(WsEvent event) {
    String payload = json.writeValueAsString(new Page(feed, List.of(event)));
    for (Subscriber s : subscribers) {
      s.data(false, payload);
    }
  }
}
