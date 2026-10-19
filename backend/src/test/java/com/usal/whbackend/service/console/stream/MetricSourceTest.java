package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamFixtures.JSON;
import static com.usal.whbackend.service.console.stream.StreamFixtures.subscriber;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.metrics.MetricsQueryRequest;
import com.usal.whbackend.api.metrics.MetricsQueryResponse;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.console.stream.StreamFixtures.MutableClock;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MetricSourceTest {

  private static final Set<UserRole> ROLES = Set.of(UserRole.SUPERADMIN);
  private static final SourceKey.Metric KEY =
      new SourceKey.Metric(
          "http_requests",
          Duration.ofMinutes(5),
          "10s",
          Map.of("route", "/orders"),
          List.of("status_class"),
          "p95",
          ROLES);

  private final MetricsQueryService metrics = mock(MetricsQueryService.class);
  private final MutableClock clock = new MutableClock(Instant.ofEpochSecond(1_000_007));
  private final MetricSource source = new MetricSource(KEY, metrics, JSON, clock);
  private final RecordingSink a = new RecordingSink();

  private static MetricsQueryResponse response(double value) {
    return new MetricsQueryResponse(
        "http_requests",
        "ms",
        "10s",
        List.of(
            new MetricsQueryResponse.Series(
                Map.of("status_class", "2xx"), List.of(List.of(1_000_000L, value)))));
  }

  @Test
  void queriesTheEpochAlignedWindowLikeTheConsoleDoes() {
    when(metrics.query(any(), eq(ROLES))).thenReturn(response(1));

    source.evaluate();

    ArgumentCaptor<MetricsQueryRequest> request =
        ArgumentCaptor.forClass(MetricsQueryRequest.class);
    verify(metrics).query(request.capture(), eq(ROLES));
    // to = floor(now / step) * step, from = to - range + step
    assertThat(request.getValue())
        .isEqualTo(
            new MetricsQueryRequest(
                "http_requests",
                Instant.ofEpochSecond(999_710),
                Instant.ofEpochSecond(1_000_000),
                "10s",
                Map.of("route", "/orders"),
                List.of("status_class"),
                "p95"));
  }

  /** The {@code /metrics/query} body plus the window it answers (additive field). */
  private static String withWindow(String response) {
    return response.substring(0, response.length() - 1)
        + ",\"window\":{\"from\":\"1970-01-12T13:41:50Z\",\"to\":\"1970-01-12T13:46:40Z\","
        + "\"step\":\"10s\"}}";
  }

  @Test
  void eachFrameCarriesTheWindowItAnswersSoTheClientNeedNotGuessTheGrid() {
    when(metrics.query(any(), eq(ROLES))).thenReturn(response(1));
    source.join(subscriber("cpu", a));

    source.evaluate();
    clock.set(Instant.ofEpochSecond(1_000_017));
    source.evaluate();

    assertThat(a.frames().get(0))
        .contains(
            "\"window\":{\"from\":\"1970-01-12T13:41:50Z\",\"to\":\"1970-01-12T13:46:40Z\","
                + "\"step\":\"10s\"}");
    // The window moved one step, so the frame changed although the series did not.
    assertThat(a.frames()).hasSize(2);
    assertThat(a.frames().get(1)).contains("\"to\":\"1970-01-12T13:46:50Z\"");
  }

  @Test
  void sendsASnapshotThenOnlyChanges() {
    when(metrics.query(any(), eq(ROLES))).thenReturn(response(1), response(1), response(2));
    source.join(subscriber("cpu", a));
    assertThat(a.frames()).isEmpty();

    source.evaluate();
    source.evaluate();
    source.evaluate();

    String one = withWindow(JSON.writeValueAsString(response(1)));
    String two = withWindow(JSON.writeValueAsString(response(2)));
    assertThat(a.frames())
        .containsExactly(
            "event: snapshot\ndata: {\"id\":\"cpu\",\"mode\":\"replace\",\"data\":" + one + "}\n\n",
            "event: update\ndata: {\"id\":\"cpu\",\"mode\":\"replace\",\"data\":" + two + "}\n\n");
  }

  @Test
  void aLateSubscriberGetsTheLastResultAtOnce() {
    when(metrics.query(any(), eq(ROLES))).thenReturn(response(1));
    source.join(subscriber("x", a));
    source.evaluate();
    RecordingSink b = new RecordingSink();

    source.join(subscriber("y", b));

    assertThat(b.frames()).hasSize(1);
    assertThat(b.frames().get(0)).startsWith("event: snapshot\ndata: {\"id\":\"y\"");
    verify(metrics, times(1)).query(any(), any());
  }

  @Test
  void aHiddenMetricIsAPermanentErrorThatNamesNothing() {
    when(metrics.query(any(), eq(ROLES)))
        .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_METRIC"));
    source.join(subscriber("x", a));

    source.evaluate();
    source.evaluate();
    RecordingSink late = new RecordingSink();
    source.join(subscriber("y", late));

    assertThat(a.frames())
        .containsExactly(
            "event: error\ndata: {\"id\":\"x\",\"code\":\"UNKNOWN_METRIC\","
                + "\"message\":\"La suscripción no es válida.\"}\n\n");
    assertThat(a.text()).doesNotContain("http_requests");
    assertThat(late.frames()).hasSize(1);
    assertThat(late.text()).contains("UNKNOWN_METRIC");
    // Permanent: the store is not asked again.
    verify(metrics, times(1)).query(any(), any());
  }

  @Test
  void anOutageIsReportedOnceRetriedAndFollowedByFreshData() {
    when(metrics.query(any(), eq(ROLES)))
        .thenReturn(response(1))
        .thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE"))
        .thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE"))
        .thenReturn(response(1));
    source.join(subscriber("x", a));

    for (int i = 0; i < 4; i++) {
      source.evaluate();
    }

    assertThat(a.frames()).hasSize(3);
    assertThat(a.frames().get(0)).startsWith("event: snapshot");
    assertThat(a.frames().get(1))
        .isEqualTo(
            "event: error\ndata: {\"id\":\"x\",\"code\":\"METRICS_UNAVAILABLE\","
                + "\"message\":\"La fuente de datos no está disponible; se reintenta.\"}\n\n");
    // The same data is sent again after an error: the client may have cleared the panel.
    assertThat(a.frames().get(2)).startsWith("event: update");
  }

  @Test
  void anUnexpectedFailureIsATransientError() {
    when(metrics.query(any(), eq(ROLES))).thenThrow(new IllegalStateException("boom"));
    source.join(subscriber("x", a));

    source.evaluate();
    source.evaluate();

    assertThat(a.frames()).hasSize(1);
    assertThat(a.text()).contains("SOURCE_UNAVAILABLE").doesNotContain("boom");
    verify(metrics, times(2)).query(any(), any());
  }

  @Test
  void aSubscriberThatJoinsDuringAnOutageGetsTheErrorThenASnapshot() {
    when(metrics.query(any(), eq(ROLES)))
        .thenThrow(
            new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE"))
        .thenReturn(response(1));
    source.evaluate();
    source.join(subscriber("x", a));

    source.evaluate();

    assertThat(a.frames()).hasSize(2);
    assertThat(a.frames().get(0)).startsWith("event: error");
    assertThat(a.frames().get(1)).startsWith("event: snapshot");
  }

  @Test
  void leavingReportsWhetherAnySubscriberIsLeft() {
    Subscriber x = subscriber("x", a);
    Subscriber y = subscriber("y", new RecordingSink());
    source.join(x);
    source.join(y);

    assertThat(source.leave(x)).isFalse();
    assertThat(source.leave(y)).isTrue();
  }
}
