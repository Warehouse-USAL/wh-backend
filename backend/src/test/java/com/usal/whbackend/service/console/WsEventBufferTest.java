package com.usal.whbackend.service.console;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class WsEventBufferTest {

  private static WsEvent event(int n) {
    return new WsEvent(Instant.EPOCH.plusSeconds(n), "push", "e" + n);
  }

  @Test
  void bufferKeepsOnlyTheLast200PerFeed() {
    WsEventBuffer buffer = new WsEventBuffer();
    for (int i = 0; i < 250; i++) {
      buffer.record("/ws/v1/orders", event(i));
    }

    var recent = buffer.recent("/ws/v1/orders");

    assertThat(recent).hasSize(200);
    assertThat(recent.getFirst().detail()).isEqualTo("e249");
    assertThat(recent.getLast().detail()).isEqualTo("e50");
  }

  @Test
  void feedsAreIndependent() {
    WsEventBuffer buffer = new WsEventBuffer();
    buffer.record("/ws/v1/orders", event(1));
    buffer.record("/ws/v1/vehicles", event(2));

    assertThat(buffer.recent("/ws/v1/orders")).extracting(WsEvent::detail).containsExactly("e1");
    assertThat(buffer.recent("/ws/v1/vehicles")).extracting(WsEvent::detail).containsExactly("e2");
    assertThat(buffer.recent("/ws/v1/stock/alerts")).isEmpty();
  }
}
