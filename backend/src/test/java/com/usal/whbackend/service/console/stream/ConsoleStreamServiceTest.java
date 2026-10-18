package com.usal.whbackend.service.console.stream;

import static com.usal.whbackend.service.console.stream.StreamRequestParserTest.logs;
import static com.usal.whbackend.service.console.stream.StreamRequestParserTest.metric;
import static com.usal.whbackend.service.console.stream.StreamRequestParserTest.status;
import static com.usal.whbackend.service.console.stream.StreamRequestParserTest.ws;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.usal.whbackend.api.metrics.MetricsQueryResponse;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaLogsRepository;
import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEvent;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

class ConsoleStreamServiceTest {

  private static final Set<UserRole> ROLES = Set.of(UserRole.SUPERADMIN);
  private static final Duration WAIT = Duration.ofSeconds(3);

  private final ConsoleProperties properties = new ConsoleProperties();
  private final MetricsQueryService metrics = mock(MetricsQueryService.class);
  private final ServiceStatusService status = mock(ServiceStatusService.class);
  private final WsEventBuffer buffer = new WsEventBuffer();
  private ConsoleStreamService service;

  private ConsoleStreamService service() {
    service =
        new ConsoleStreamService(
            properties,
            metrics,
            new LogsQueryService(mock(VictoriaLogsRepository.class)),
            status,
            buffer,
            StreamFixtures.JSON);
    service.start();
    return service;
  }

  @AfterEach
  void tearDown() {
    if (service != null) {
      service.destroy();
    }
  }

  private static StreamRequest request(StreamRequest.Subscription... subs) {
    return new StreamRequest(Arrays.asList(subs));
  }

  private StreamSession open(
      String principal, RecordingSink sink, StreamRequest.Subscription... s) {
    return service.open(request(s), principal, ROLES, null, "req-1", sink);
  }

  private static WsEvent event(String detail) {
    return new WsEvent(Instant.parse("2026-10-18T12:00:00Z"), "push", detail);
  }

  @Test
  void pushesTheBufferThenEachNewWebSocketEvent() {
    service();
    buffer.record("/ws/v1/orders", event("old"));
    RecordingSink sink = new RecordingSink();

    open("u1", sink, ws("w", "orders"));
    await().atMost(WAIT).until(() -> sink.frames().size() == 1);
    buffer.record("/ws/v1/orders", event("new"));

    await().atMost(WAIT).until(() -> sink.frames().size() == 2);
    assertThat(sink.frames().get(0)).startsWith("event: snapshot").contains("\"detail\":\"old\"");
    assertThat(sink.frames().get(1))
        .startsWith("event: update\ndata: {\"id\":\"w\",\"mode\":\"append\"")
        .contains("\"detail\":\"new\"")
        .doesNotContain("\"old\"");
  }

  @Test
  void anInvalidRequestIsRejectedBeforeAnythingOpens() {
    service();

    assertThatThrownBy(() -> open("u1", new RecordingSink(), status("a"), status("a")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> assertThat(e.getReason()).isEqualTo("INVALID_SUBSCRIPTION"));
    assertThatThrownBy(() -> open("u1", new RecordingSink(), logs("l", null, "a|b", "1h")))
        .hasMessageContaining("INVALID_FILTER_VALUE");

    assertThat(service.openStreams()).isZero();
    assertThat(service.activeSources()).isZero();
  }

  @Test
  void enforcesThePerPrincipalAndGlobalStreamLimits() {
    properties.getStream().setMaxStreamsPerPrincipal(2);
    properties.getStream().setMaxStreams(3);
    service();
    RecordingSink first = new RecordingSink();

    open("u1", first, ws("w", "orders"));
    open("u1", new RecordingSink(), ws("w", "orders"));
    assertThatThrownBy(() -> open("u1", new RecordingSink(), ws("w", "orders")))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(429);
              assertThat(e.getReason()).isEqualTo("STREAM_LIMIT");
            });
    open("u2", new RecordingSink(), ws("w", "orders"));
    assertThatThrownBy(() -> open("u3", new RecordingSink(), ws("w", "orders")))
        .hasMessageContaining("STREAM_LIMIT");

    first.clientGone();
    await().atMost(WAIT).until(() -> service.openStreams() == 2);
    open("u1", new RecordingSink(), ws("w", "orders"));
    assertThat(service.openStreams()).isEqualTo(3);
  }

  @Test
  void oneQueryServesEveryStreamThatSubscribesToIt() {
    properties.getStream().setMetricInterval(Duration.ofHours(1));
    when(metrics.query(any(), any()))
        .thenReturn(new MetricsQueryResponse("http_requests", "ms", "10s", List.of()));
    service();
    RecordingSink a = new RecordingSink();
    RecordingSink b = new RecordingSink();

    open("u1", a, metric("m", "5m", "10s", null, null));
    await().atMost(WAIT).until(() -> a.frames().size() == 1);
    open(
        "u2",
        b,
        metric("other-id", "5m", "10s", null, null),
        metric("same", "5m", "10s", null, null));

    await().atMost(WAIT).until(() -> b.frames().size() == 2);
    verify(metrics, times(1)).query(any(), any());
    assertThat(service.activeSources()).isEqualTo(1);
  }

  @Test
  void aClosedStreamReleasesEverySourceItHeld() {
    when(status.statuses()).thenReturn(List.of());
    service();
    RecordingSink sink = new RecordingSink();
    open("u1", sink, ws("w", "orders"), status("s"), logs("l", null, null, "5m"));
    assertThat(service.activeSources()).isEqualTo(3);

    sink.clientGone();

    await().atMost(WAIT).until(() -> service.activeSources() == 0 && service.openStreams() == 0);
    int before = sink.frames().size();
    buffer.record("/ws/v1/orders", event("after"));
    assertThat(sink.frames()).hasSize(before);
  }

  @Test
  void theStreamEndsWhenItsTokenExpires() {
    service();
    RecordingSink sink = new RecordingSink();

    service.open(
        request(ws("w", "orders")), "u1", ROLES, Instant.now().plusMillis(200), "req-1", sink);

    await().atMost(WAIT).until(sink::completed);
    assertThat(sink.frames())
        .last()
        .isEqualTo("event: end\ndata: {\"reason\":\"token-expired\"}\n\n");
    await().atMost(WAIT).until(() -> service.activeSources() == 0 && service.openStreams() == 0);
  }

  @Test
  void anAlreadyExpiredTokenEndsTheStreamAtOnce() {
    service();
    RecordingSink sink = new RecordingSink();

    service.open(request(ws("w", "orders")), "u1", ROLES, Instant.now().minusSeconds(1), "r", sink);

    await().atMost(WAIT).until(sink::completed);
    assertThat(sink.text()).contains("token-expired");
  }

  @Test
  void idleStreamsGetAHeartbeat() {
    properties.getStream().setHeartbeat(Duration.ofMillis(50));
    service();
    RecordingSink sink = new RecordingSink();

    open("u1", sink, ws("w", "orders"));

    await().atMost(WAIT).until(() -> sink.frames().contains(": ping\n\n"));
  }

  @Test
  void aClientThatStopsReadingIsDroppedAndItsSourcesReleased() {
    properties.getStream().setHeartbeat(Duration.ofMillis(30));
    properties.getStream().setSendTimeout(Duration.ofMillis(100));
    service();
    RecordingSink stuck = new RecordingSink();
    stuck.block();
    RecordingSink healthy = new RecordingSink();

    StreamSession session = open("u1", stuck, ws("w", "orders"));
    open("u2", healthy, ws("w", "orders"));

    await().atMost(WAIT).until(() -> !session.isOpen());
    assertThat(session.closeReason()).isEqualTo("send-timeout");
    buffer.record("/ws/v1/orders", event("still flowing"));
    await().atMost(WAIT).until(() -> healthy.text().contains("still flowing"));
    assertThat(service.openStreams()).isEqualTo(1);
    stuck.release();
  }

  @Test
  void shutdownEndsEveryStream() {
    service();
    RecordingSink sink = new RecordingSink();
    open("u1", sink, ws("w", "orders"));

    service.stop();

    assertThat(service.isRunning()).isFalse();
    assertThat(sink.completed()).isTrue();
    assertThat(sink.frames())
        .last()
        .isEqualTo("event: end\ndata: {\"reason\":\"server-shutdown\"}\n\n");
    await().atMost(WAIT).until(() -> service.openStreams() == 0);
  }

  @Test
  void logsOpenAndCloseWithCountsAndTheRequestIdButNoPrincipal() {
    Logger logger = (Logger) LoggerFactory.getLogger(ConsoleStreamService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service();
      RecordingSink sink = new RecordingSink();
      MDC.put("request_id", "req-7");
      try {
        service.open(
            request(ws("a", "orders"), ws("b", "vehicles")),
            "ana@example.com",
            ROLES,
            null,
            "req-7",
            sink);
      } finally {
        MDC.remove("request_id");
      }
      sink.clientGone();
      await().atMost(WAIT).until(() -> appender.list.size() >= 2);

      assertThat(appender.list)
          .extracting(ILoggingEvent::getFormattedMessage)
          .anySatisfy(m -> assertThat(m).contains("opened").contains("2 subscriptions"))
          .anySatisfy(
              m -> assertThat(m).contains("closed (client-gone)").contains("2 subscriptions"))
          .allSatisfy(m -> assertThat(m).doesNotContain("ana@example.com"));
      assertThat(appender.list)
          .allSatisfy(e -> assertThat(e.getMDCPropertyMap()).containsEntry("request_id", "req-7"));
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void theHttpEntryPointBoundsTheEmitterByTheTokenLifetime() {
    service();
    Instant expiry = Instant.now().plus(Duration.ofMinutes(10));

    ResponseBodyEmitter withToken = service.open(request(status("s")), "u1", ROLES, expiry, "r");
    ResponseBodyEmitter withoutToken = service.open(request(status("s")), "u2", ROLES, null, "r");

    assertThat(withToken.getTimeout())
        .isBetween(Duration.ofMinutes(10).toMillis(), Duration.ofMinutes(12).toMillis());
    assertThat(withoutToken.getTimeout()).isZero();
    assertThat(service.openStreams()).isEqualTo(2);
  }
}
