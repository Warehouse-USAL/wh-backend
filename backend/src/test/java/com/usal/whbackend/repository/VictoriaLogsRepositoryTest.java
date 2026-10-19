package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.service.console.LogLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class VictoriaLogsRepositoryTest {

  private static final Instant FROM = Instant.parse("2026-10-18T10:00:00Z");
  private static final Instant TO = Instant.parse("2026-10-18T11:00:00Z");

  private record Fixture(VictoriaLogsRepository repository, MockRestServiceServer server) {}

  private static Fixture fixture() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://victorialogs:9428");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    return new Fixture(new VictoriaLogsRepository(builder.build(), ""), server);
  }

  @Test
  void buildsLogsQlFromFixedFilters() {
    assertThat(VictoriaLogsRepository.buildQuery("bridge", "error", "7f3a", "timeout"))
        .isEqualTo(
            "\"service.name\":=\"wh-bridge\" \"severity_text\":i(\"error\")"
                + " \"request_id\":=\"7f3a\" i(\"timeout\") | sort by (_time) desc");
  }

  @Test
  void noFiltersMatchesEverything() {
    assertThat(VictoriaLogsRepository.buildQuery(null, null, null, null))
        .isEqualTo("* | sort by (_time) desc");
  }

  @Test
  void sendsQueryRangeAndLimit() {
    Fixture f = fixture();
    f.server()
        .expect(
            requestTo(
                "http://victorialogs:9428/select/logsql/query?query=*%20%7C%20sort%20by%20%28_time%29%20desc"
                    + "&start=2026-10-18T10:00:00Z&end=2026-10-18T11:00:00Z&limit=50"))
        .andRespond(withSuccess("", MediaType.APPLICATION_NDJSON));

    assertThat(f.repository().search(null, null, null, null, FROM, TO, 50)).isEmpty();
    f.server().verify();
  }

  @Test
  void parsesNdjsonDefensively() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("select/logsql/query")))
        .andRespond(
            withSuccess(
                """
                {"_time":"2026-10-18T10:30:00Z","_msg":"boom","severity_text":"ERROR","service.name":"wh-backend","request_id":"abc"}

                not json
                [1,2]
                {"_time":"garbage","_msg":null}
                {"_msg":"bare"}
                """,
                MediaType.APPLICATION_NDJSON));

    List<LogLine> lines = f.repository().search(null, null, null, null, FROM, TO, 10);

    assertThat(lines)
        .containsExactly(
            new LogLine(Instant.parse("2026-10-18T10:30:00Z"), "error", "backend", "boom", "abc"),
            new LogLine(null, null, null, null, null),
            new LogLine(null, null, null, "bare", null));
  }

  /** Lines as VictoriaLogs v1.53.0 stores them from OTLP (verified in Task 15). */
  @Test
  void levelComesFromSeverityTextLowercased() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("select/logsql/query")))
        .andRespond(
            withSuccess(
                """
                {"_msg":"a","_time":"2026-10-18T18:18:03.249950757Z","request_id":"t15abc","service.name":"wh-backend","severity_number":"13","severity_text":"WARN"}
                {"_msg":"b","service.name":"wh-bridge","severity_number":"9","severity_text":"INFO"}
                {"_msg":"c","severity_text":"DEBUG"}
                {"_msg":"d","severity":"ERROR"}
                """,
                MediaType.APPLICATION_NDJSON));

    List<LogLine> lines = f.repository().search(null, null, null, null, FROM, TO, 10);

    assertThat(lines)
        .containsExactly(
            new LogLine(
                Instant.parse("2026-10-18T18:18:03.249950757Z"), "warn", "backend", "a", "t15abc"),
            new LogLine(null, "info", "bridge", "b", null),
            new LogLine(null, "debug", null, "c", null),
            new LogLine(null, null, null, "d", null));
  }

  @Test
  void emptyBodyYieldsNoLines() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("select/logsql/query")))
        .andRespond(withSuccess());

    assertThat(f.repository().search(null, null, null, null, FROM, TO, 10)).isEmpty();
  }

  @Test
  void mapsShortServiceNamesToStoreNames() {
    assertThat(VictoriaLogsRepository.buildQuery("backend", null, null, null))
        .startsWith("\"service.name\":=\"wh-backend\" ");
    assertThat(VictoriaLogsRepository.buildQuery("bridge", null, null, null))
        .startsWith("\"service.name\":=\"wh-bridge\" ");
  }

  @Test
  void newestFirstOrderingIsPartOfTheQuery() {
    assertThat(VictoriaLogsRepository.buildQuery(null, null, null, "x"))
        .endsWith(" | sort by (_time) desc");
  }

  @Test
  void logsQlOperatorsInTextStayInsideOneQuotedPhrase() {
    assertThat(
            VictoriaLogsRepository.buildQuery(null, null, null, "level:error _msg:~x -x (a OR b)*"))
        .isEqualTo("i(\"level:error _msg:~x -x (a OR b)*\") | sort by (_time) desc");
  }

  @Test
  void encodesReservedCharactersStrictlyOnTheWire() {
    Fixture f = fixture();
    f.server()
        .expect(
            requestTo(
                "http://victorialogs:9428/select/logsql/query?query=i%28%22a%2Bb%3Bc%20d%22%29"
                    + "%20%7C%20sort%20by%20%28_time%29%20desc"
                    + "&start=2026-10-18T10:00:00Z&end=2026-10-18T11:00:00Z&limit=5"))
        .andRespond(withSuccess("", MediaType.APPLICATION_NDJSON));

    f.repository().search(null, null, null, "a+b;c d", FROM, TO, 5);
    f.server().verify();
  }

  @Test
  void storeDownReturns502LogStoreUnavailable() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("select/logsql/query")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> f.repository().search(null, null, null, null, FROM, TO, 10))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              assertThat(ex.getReason()).isEqualTo("LOG_STORE_UNAVAILABLE");
            });
  }

  @Test
  void publicConstructorBuildsAClientFromProperties() {
    ConsoleProperties properties = new ConsoleProperties();
    assertThat(properties.getVictorialogsUrl()).isEqualTo("http://victorialogs:9428");
    properties.setVictorialogsUrl("http://localhost:1");

    VictoriaLogsRepository repository = new VictoriaLogsRepository(properties);

    assertThatThrownBy(() -> repository.search(null, null, null, null, FROM, TO, 1))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            ex -> {
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              assertThat(ex.getReason()).isEqualTo("LOG_STORE_UNAVAILABLE");
            });
  }
}
