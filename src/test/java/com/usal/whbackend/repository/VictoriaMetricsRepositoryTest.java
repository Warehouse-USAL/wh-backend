package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

class VictoriaMetricsRepositoryTest {

  private static final Instant FROM = Instant.parse("2026-08-20T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-08-20T01:00:00Z");

  private record Fixture(VictoriaMetricsRepository repository, MockRestServiceServer server) {}

  private static Fixture fixture() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://victoriametrics:8428");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    return new Fixture(new VictoriaMetricsRepository(builder.build()), server);
  }

  @Test
  void parsesMatrixResultIntoSeries() {
    Fixture f = fixture();
    f.server()
        .expect(MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"__name__":"wh_vehicle_battery","vehicle_id":"VHC-001"},
                   "values":[[1755648000,"79"],[1755648300,"78.4"]]}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    List<VictoriaMetricsRepository.TimeSeries> series =
        f.repository().queryRange("avg(wh_vehicle_battery)", FROM, TO, "5m");

    assertThat(series).hasSize(1);
    // __name__ is storage bookkeeping and must not leak into the response labels.
    assertThat(series.get(0).labels())
        .containsExactly(java.util.Map.entry("vehicle_id", "VHC-001"));
    assertThat(series.get(0).points())
        .containsExactly(List.of(1755648000L, 79.0), List.of(1755648300L, 78.4));
  }

  @Test
  void sendsTheQueryAndRangeAsQueryParameters() {
    Fixture f = fixture();
    f.server()
        .expect(
            requestTo(
                "http://victoriametrics:8428/api/v1/query_range"
                    + "?query=avg(wh_vehicle_battery)"
                    + "&start="
                    + FROM.getEpochSecond()
                    + "&end="
                    + TO.getEpochSecond()
                    + "&step=5m"))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}",
                MediaType.APPLICATION_JSON));

    f.repository().queryRange("avg(wh_vehicle_battery)", FROM, TO, "5m");

    f.server().verify();
  }

  @Test
  void mapsATransportFailureToMetricsUnavailable() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("query_range")))
        .andRespond(withServerError());

    assertThatThrownBy(() -> f.repository().queryRange("up", FROM, TO, "5m"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            t -> {
              ResponseStatusException ex = (ResponseStatusException) t;
              assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getReason()).isEqualTo("METRICS_UNAVAILABLE");
            });
  }

  @Test
  void emptyResultYieldsNoSeries() {
    Fixture f = fixture();
    f.server()
        .expect(MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}",
                MediaType.APPLICATION_JSON));

    assertThat(f.repository().queryRange("up", FROM, TO, "5m")).isEmpty();
  }

  @Test
  void malformedPointsAreDroppedRatherThanCrashingTheQuery() {
    Fixture f = fixture();
    f.server()
        .expect(MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"vehicle_id":"VHC-001"},
                   "values":[[1755648000,"NaN-ish"],[1755648300,"78.4"],[1755648600]]}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    List<VictoriaMetricsRepository.TimeSeries> series =
        f.repository().queryRange("up", FROM, TO, "5m");

    assertThat(series.get(0).points()).containsExactly(List.of(1755648300L, 78.4));
  }
}
