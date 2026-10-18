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
    assertThat(VictoriaLogsRepository.buildQuery("wh-bridge", "error", "7f3a", "timeout"))
        .isEqualTo(
            "\"service.name\":=\"wh-bridge\" \"severity\":i(\"error\")"
                + " \"request_id\":=\"7f3a\" \"timeout\" | sort by (_time) desc");
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
                "http://victorialogs:9428/select/logsql/query?query=*%20%7C%20sort%20by%20(_time)%20desc"
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
                {"_time":"2026-10-18T10:30:00Z","_msg":"boom","severity":"ERROR","service.name":"wh-backend","request_id":"abc"}

                not json
                [1,2]
                {"_time":"garbage","_msg":null}
                {"_msg":"bare"}
                """,
                MediaType.APPLICATION_NDJSON));

    List<LogLine> lines = f.repository().search(null, null, null, null, FROM, TO, 10);

    assertThat(lines)
        .containsExactly(
            new LogLine(
                Instant.parse("2026-10-18T10:30:00Z"), "ERROR", "wh-backend", "boom", "abc"),
            new LogLine(null, null, null, null, null),
            new LogLine(null, null, null, "bare", null));
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
        .isInstanceOf(ResponseStatusException.class);
  }
}
