package com.usal.whbackend.api.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.telemetry.NoOpTelemetryAdapter;
import com.usal.whbackend.telemetry.TelemetryPort;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WsActivityTest {

  private static final String FEED = WsActivity.ORDERS;

  private final TelemetryPort telemetry = mock(TelemetryPort.class);
  private final WsEventBuffer buffer = new WsEventBuffer();
  private final WsActivity activity = new WsActivity(buffer, telemetry);

  @Test
  void connectedAndClosedMoveTheGaugeAndRecordEvents() {
    activity.connected(FEED, "session opened");
    activity.closed(FEED, "session closed");

    verify(telemetry).recordWsSession(FEED, 1);
    verify(telemetry).recordWsSession(FEED, -1);
    verify(telemetry).recordWsMessage(FEED, "closed");
    assertThat(buffer.recent(FEED)).extracting("kind").containsExactly("close", "connect");
  }

  @Test
  void closedWithoutConnectNeverDrivesTheGaugeNegative() {
    activity.closed(FEED, "orphan");

    verify(telemetry, never()).recordWsSession(FEED, -1);
    verify(telemetry).recordWsMessage(FEED, "closed");
    assertThat(buffer.recent(FEED)).hasSize(1);
  }

  @Test
  void pushedIsCountedAndRecorded() {
    activity.pushed(FEED, "order.updated o_1 status ASSIGNED");

    verify(telemetry).recordWsMessage(FEED, "pushed");
    assertThat(buffer.recent(FEED)).extracting("kind").containsExactly("push");
  }

  @Test
  void detailNeverContainsQueryString() {
    activity.rejected(FEED, "GET /ws/v1/orders?token=abc");
    activity.rejected(FEED, "bad token=abc here");

    verify(telemetry, org.mockito.Mockito.times(2)).recordWsMessage(FEED, "rejected");
    assertThat(buffer.recent(FEED))
        .extracting("kind", "detail")
        .allSatisfy(t -> assertThat(t.toString()).doesNotContain("abc"));
  }

  @Test
  void longDetailsAreTruncated() {
    activity.pushed(FEED, "x".repeat(500));

    assertThat(buffer.recent(FEED).getFirst().detail()).hasSizeLessThanOrEqualTo(120);
  }

  @Test
  void nullDetailIsStoredEmpty() {
    activity.pushed(FEED, null);

    assertThat(buffer.recent(FEED).getFirst().detail()).isEmpty();
  }

  @Test
  void concurrentConnectsAndClosesLeaveTheGaugeAtZero() throws Exception {
    AtomicInteger gauge = new AtomicInteger();
    TelemetryPort counting =
        new NoOpTelemetryAdapter() {
          @Override
          public void recordWsSession(String feed, int delta) {
            gauge.addAndGet(delta);
          }
        };
    WsActivity concurrent = new WsActivity(new WsEventBuffer(), counting);
    int threads = 8;
    int sessions = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch go = new CountDownLatch(1);
    try {
      for (int t = 0; t < threads; t++) {
        pool.submit(
            () -> {
              go.await();
              for (int i = 0; i < sessions; i++) {
                concurrent.connected(FEED, "session opened");
                concurrent.closed(FEED, "session closed");
              }
              return null;
            });
      }
      go.countDown();
    } finally {
      pool.shutdown();
    }
    assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

    // Every close matched a connect, so no decrement was dropped and none was invented.
    assertThat(gauge).hasValue(0);
  }
}
