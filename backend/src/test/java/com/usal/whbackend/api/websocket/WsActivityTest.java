package com.usal.whbackend.api.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.telemetry.TelemetryPort;
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
}
