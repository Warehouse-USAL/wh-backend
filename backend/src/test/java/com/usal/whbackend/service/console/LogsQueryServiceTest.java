package com.usal.whbackend.service.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.repository.VictoriaLogsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

class LogsQueryServiceTest {

  private static final Instant TO = Instant.parse("2026-10-18T11:00:00Z");

  private final VictoriaLogsRepository repository = mock(VictoriaLogsRepository.class);
  private final LogsQueryService service = new LogsQueryService(repository);

  private static LogsQuery query(String service, String level, String requestId, String text) {
    return new LogsQuery(service, level, requestId, text, TO.minusSeconds(60), TO, 10);
  }

  private void assertCode(LogsQuery q, String code) {
    assertThatThrownBy(() -> service.query(q))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode().value()).isEqualTo(400);
              assertThat(ex.getReason()).isEqualTo(code);
            });
  }

  @Test
  void passesValidatedFiltersToTheRepository() {
    LogLine line = new LogLine(TO, "error", "wh-bridge", "m", "7f3a");
    when(repository.search(any(), any(), any(), any(), any(), any(), anyInt()))
        .thenReturn(List.of(line));

    List<LogLine> result = service.query(query("wh-bridge", "error", "7f3a", "timeout"));

    assertThat(result).containsExactly(line);
    verify(repository).search("wh-bridge", "error", "7f3a", "timeout", TO.minusSeconds(60), TO, 10);
  }

  @Test
  void blankFiltersAreIgnored() {
    service.query(query(" ", "", null, "  "));

    verify(repository).search(null, null, null, null, TO.minusSeconds(60), TO, 10);
  }

  @ParameterizedTest
  @ValueSource(strings = {"a\"b", "a{b", "a}b", "a|b", "a\\b", "a\nb", "a\rb", "a`b", "a\" or *"})
  void rejectsInjectionInFilters(String bad) {
    assertCode(query(null, null, null, bad), "INVALID_FILTER_VALUE");
    assertCode(query(null, null, bad, null), "INVALID_FILTER_VALUE");
    assertCode(query(bad, null, null, null), "INVALID_FILTER_VALUE");
    assertCode(query(null, bad, null, null), "INVALID_FILTER_VALUE");
  }

  @Test
  void rejectsValuesOutsideTheAllowLists() {
    assertCode(query("other", null, null, null), "INVALID_FILTER_VALUE");
    assertCode(query(null, "ERROR", null, null), "INVALID_FILTER_VALUE");
    assertCode(query(null, null, "x".repeat(65), null), "INVALID_FILTER_VALUE");
    assertCode(query(null, null, null, "x".repeat(201)), "INVALID_FILTER_VALUE");
  }

  @Test
  void acceptsMaxLengthTextAndAllServices() {
    service.query(query("wh-backend", "debug", null, "x".repeat(200)));
    service.query(query("restock-cron", "warn", null, "free text: ok"));
    verify(repository).search(eq("restock-cron"), eq("warn"), any(), any(), any(), any(), eq(10));
  }

  @Test
  void limitDefaultsTo100AndIsCappedAt500() {
    service.query(new LogsQuery(null, null, null, null, TO.minusSeconds(60), TO, 0));
    service.query(new LogsQuery(null, null, null, null, TO.minusSeconds(60), TO, 9999));

    ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
    verify(repository, org.mockito.Mockito.times(2))
        .search(any(), any(), any(), any(), any(), any(), limit.capture());
    assertThat(limit.getAllValues()).containsExactly(100, 500);
  }

  @Test
  void limitIsCappedAt500Exactly() {
    service.query(new LogsQuery(null, null, null, null, TO.minusSeconds(60), TO, 501));
    verify(repository).search(any(), any(), any(), any(), any(), any(), eq(500));
  }

  @Test
  void rangeLongerThan7DaysIs400() {
    assertCode(
        new LogsQuery(null, null, null, null, TO.minus(Duration.ofDays(7)).minusSeconds(1), TO, 1),
        "INVALID_TIME_RANGE");
  }

  @Test
  void rangeOfExactly7DaysIsAllowed() {
    service.query(new LogsQuery(null, null, null, null, TO.minus(Duration.ofDays(7)), TO, 1));
    verify(repository).search(any(), any(), any(), any(), any(), eq(TO), eq(1));
  }

  @Test
  void fromAfterToIs400() {
    assertCode(
        new LogsQuery(null, null, null, null, TO.plusSeconds(1), TO, 1), "INVALID_TIME_RANGE");
  }

  @Test
  void defaultsToTheLastHourEndingNow() {
    Instant before = Instant.now();
    service.query(new LogsQuery(null, null, null, null, null, null, 1));

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(repository).search(any(), any(), any(), any(), from.capture(), to.capture(), eq(1));
    assertThat(to.getValue()).isBetween(before, Instant.now());
    assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void fromDefaultsRelativeToAnExplicitTo() {
    service.query(new LogsQuery(null, null, null, null, null, TO, 1));
    verify(repository)
        .search(any(), any(), any(), any(), eq(TO.minus(Duration.ofHours(1))), eq(TO), eq(1));
  }
}
