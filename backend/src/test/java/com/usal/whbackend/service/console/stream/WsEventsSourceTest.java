package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamFixtures.JSON;
import static com.usal.whbackend.service.console.stream.StreamFixtures.subscriber;
import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class WsEventsSourceTest {

  private static final String FEED = "/ws/v1/orders";

  private final WsEventBuffer buffer = new WsEventBuffer();
  private final WsEventsSource source =
      new WsEventsSource(new SourceKey.WsEvents(FEED), buffer, JSON);
  private final RecordingSink sink = new RecordingSink();

  private static WsEvent event(int n) {
    return new WsEvent(Instant.parse("2026-10-18T12:00:00Z").plusSeconds(n), "push", "e" + n);
  }

  private static String json(List<WsEvent> events) {
    return JSON.writeValueAsString(new WsEventsSource.Page(FEED, events));
  }

  @Test
  void snapshotIsTheBufferThenEachEventAsItHappens() {
    buffer.record(FEED, event(1));
    buffer.record(FEED, event(2));
    source.start();
    source.join(subscriber("ws", sink));

    buffer.record(FEED, event(3));
    buffer.record("/ws/v1/vehicles", event(4));

    assertThat(sink.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"ws\",\"mode\":\"append\",\"data\":"
                + json(List.of(event(2), event(1)))
                + "}\n\n",
            "event: update\ndata: {\"id\":\"ws\",\"mode\":\"append\",\"data\":"
                + json(List.of(event(3)))
                + "}\n\n");
  }

  @Test
  void stoppingDetachesFromTheBuffer() {
    source.start();
    Subscriber s = subscriber("ws", sink);
    source.join(s);

    assertThat(source.leave(s)).isTrue();
    source.stop();
    buffer.record(FEED, event(1));

    assertThat(sink.frames()).hasSize(1);
  }

  @Test
  void eventsAreEventDrivenNotPolled() {
    source.evaluate();
    assertThat(sink.frames()).isEmpty();
  }
}
