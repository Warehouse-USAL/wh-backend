package com.usal.whbackend.service.console.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.VictoriaLogsRepository;
import com.usal.whbackend.service.console.LogsQueryService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class StreamRequestParserTest {

  private static final Set<UserRole> ROLES = Set.of(UserRole.SUPERADMIN);

  private final StreamRequestParser parser =
      new StreamRequestParser(new LogsQueryService(mock(VictoriaLogsRepository.class)), 3);

  static StreamRequest.Subscription metric(
      String id, String range, String step, Map<String, String> filters, List<String> groupBy) {
    return new StreamRequest.Subscription(
        id,
        "metric",
        "http_requests",
        range,
        step,
        filters,
        groupBy,
        "p95",
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static StreamRequest.Subscription logs(String id, String level, String text, String range) {
    return new StreamRequest.Subscription(
        id, "logs", null, range, null, null, null, null, "backend", level, null, text, null, null);
  }

  static StreamRequest.Subscription ws(String id, String feed) {
    return new StreamRequest.Subscription(
        id, "ws-events", null, null, null, null, null, null, null, null, null, null, null, feed);
  }

  static StreamRequest.Subscription status(String id) {
    return new StreamRequest.Subscription(
        id, "status", null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private List<ParsedSubscription> parse(StreamRequest.Subscription... subs) {
    return parser.parse(new StreamRequest(Arrays.asList(subs)), ROLES);
  }

  private void assertRejected(String code, StreamRequest.Subscription... subs) {
    assertThatThrownBy(() -> parse(subs))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            e -> {
              assertThat(e.getStatusCode().value()).isEqualTo(400);
              assertThat(e.getReason()).isEqualTo(code);
            });
  }

  @Test
  void parsesEveryKindIntoACanonicalKey() {
    List<ParsedSubscription> parsed =
        parse(
            metric("m.1", "15m", "10s", Map.of("route", "/orders"), List.of("status_class")),
            logs("l_1", "error", "timeout", "1h"),
            ws("w-1", "user-orders"));

    assertThat(parsed).extracting(ParsedSubscription::id).containsExactly("m.1", "l_1", "w-1");
    assertThat(parsed.get(0).key())
        .isEqualTo(
            new SourceKey.Metric(
                "http_requests",
                Duration.ofMinutes(15),
                "10s",
                Map.of("route", "/orders"),
                List.of("status_class"),
                "p95",
                ROLES));
    assertThat(parsed.get(1).key())
        .isEqualTo(
            new SourceKey.Logs("backend", "error", null, "timeout", Duration.ofHours(1), 100));
    assertThat(parsed.get(2).key()).isEqualTo(new SourceKey.WsEvents("/ws/v1/orders/{userId}"));
    assertThat(parse(status("s")).get(0).key()).isEqualTo(new SourceKey.Status());
  }

  @Test
  void equivalentMetricParamsShareOneKey() {
    Map<String, String> ab = new LinkedHashMap<>();
    ab.put("a", "1");
    ab.put("b", "2");
    Map<String, String> ba = new LinkedHashMap<>();
    ba.put("b", "2");
    ba.put("a", "1");

    List<ParsedSubscription> parsed =
        parse(
            metric("x", "1h", " 1m ", ab, List.of("y", "x")),
            metric("y", "1h", "1m", ba, List.of("x", "y")));

    assertThat(parsed.get(0).key()).isEqualTo(parsed.get(1).key());
  }

  @Test
  void aNullFilterValueReachesTheMetricsServiceToBeRejectedThere() {
    Map<String, String> filters = new LinkedHashMap<>();
    filters.put("route", null);

    assertThat(parse(metric("x", "1h", "1m", filters, null)).get(0).key())
        .isInstanceOf(SourceKey.Metric.class);
  }

  @Test
  void logsLimitIsCappedLikeTheRestEndpoint() {
    StreamRequest.Subscription big =
        new StreamRequest.Subscription(
            "l", "logs", null, "5m", null, null, null, null, null, null, "req-1", null, 9000, null);

    assertThat(parse(big).get(0).key())
        .isEqualTo(new SourceKey.Logs(null, null, "req-1", null, Duration.ofMinutes(5), 500));
  }

  @Test
  void rejectsAMissingOrEmptyList() {
    assertThatThrownBy(() -> parser.parse(new StreamRequest(null), ROLES))
        .hasMessageContaining("INVALID_SUBSCRIPTION");
    assertThatThrownBy(() -> parser.parse(null, ROLES))
        .hasMessageContaining("INVALID_SUBSCRIPTION");
    assertRejected("INVALID_SUBSCRIPTION");
    List<StreamRequest.Subscription> withNull = new ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> parser.parse(new StreamRequest(withNull), ROLES))
        .hasMessageContaining("INVALID_SUBSCRIPTION");
  }

  @Test
  void rejectsMoreSubscriptionsThanTheLimit() {
    assertRejected("INVALID_SUBSCRIPTION", status("a"), status("b"), status("c"), status("d"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "has space",
        "slash/",
        "quote\"",
        "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"
      })
  void rejectsBadIds(String id) {
    assertRejected("INVALID_SUBSCRIPTION", status(id));
  }

  @Test
  void rejectsANullIdAndDuplicateIds() {
    assertRejected("INVALID_SUBSCRIPTION", status(null));
    assertRejected("INVALID_SUBSCRIPTION", status("a"), ws("a", "orders"));
  }

  @Test
  void rejectsUnknownKinds() {
    assertRejected(
        "INVALID_SUBSCRIPTION",
        new StreamRequest.Subscription(
            "a", "trail", null, null, null, null, null, null, null, null, null, null, null, null));
    assertRejected(
        "INVALID_SUBSCRIPTION",
        new StreamRequest.Subscription(
            "a", null, null, null, null, null, null, null, null, null, null, null, null, null));
  }

  @Test
  void rejectsMetricParamsThatAreAClientBug() {
    assertRejected("INVALID_SUBSCRIPTION", metric("a", "2h", "1m", null, null));
    assertRejected("INVALID_SUBSCRIPTION", metric("a", null, "1m", null, null));
    assertRejected("INVALID_SUBSCRIPTION", metric("a", "1h", "1x", null, null));
    assertRejected("INVALID_SUBSCRIPTION", metric("a", "5m", "5m", null, null));
    assertRejected(
        "INVALID_SUBSCRIPTION",
        new StreamRequest.Subscription(
            "a", "metric", " ", "1h", "1m", null, null, "avg", null, null, null, null, null, null));
    assertRejected(
        "INVALID_SUBSCRIPTION",
        new StreamRequest.Subscription(
            "a", "metric", "m", "1h", "1m", null, null, null, null, null, null, null, null, null));
  }

  @Test
  void anUnknownMetricIsNotRejectedUpFront() {
    // Visibility depends on the catalogue and the caller, so it surfaces as an error event.
    StreamRequest.Subscription unknown =
        new StreamRequest.Subscription(
            "a", "metric", "nope", "1h", "1m", null, null, "avg", null, null, null, null, null,
            null);

    assertThat(parse(unknown)).hasSize(1);
  }

  @Test
  void logsFiltersFollowTheRestRules() {
    assertRejected("INVALID_FILTER_VALUE", logs("a", "fatal", null, "1h"));
    assertRejected("INVALID_FILTER_VALUE", logs("a", null, "x\" | delete", "1h"));
    assertRejected("INVALID_FILTER_VALUE", logs("a", null, "a{b}", "1h"));
    assertRejected("INVALID_SUBSCRIPTION", logs("a", null, null, "7d"));
    assertRejected(
        "INVALID_FILTER_VALUE",
        new StreamRequest.Subscription(
            "a", "logs", null, "1h", null, null, null, null, "mongo", null, null, null, null,
            null));
  }

  @Test
  void rejectsUnknownFeeds() {
    assertRejected("INVALID_SUBSCRIPTION", ws("a", "/ws/v1/orders"));
    assertRejected("INVALID_SUBSCRIPTION", ws("a", null));
  }
}
