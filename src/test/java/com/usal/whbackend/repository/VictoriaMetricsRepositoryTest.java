package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    return new Fixture(new VictoriaMetricsRepository(builder.build(), ""), server);
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

  @Test
  void aSelectorWithLabelFiltersSurvivesUriBuilding() {
    Fixture f = fixture();
    f.server()
        .expect(
            requestTo(
                // The braces reach VictoriaMetrics percent-encoded rather than blowing up in the
                // builder. Asserted on the selector alone: which characters beyond it get encoded
                // is the URI library's business and not what this test is about.
                org.hamcrest.Matchers.containsString("%7Bto%3D%22ERROR%22%7D")))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":[]}}",
                MediaType.APPLICATION_JSON));

    // The braces in a MetricsQL selector are a URI template variable as far as UriBuilder is
    // concerned, and it throws rather than expanding one it has no value for. Every filtered
    // query failed this way until the URI was built pre-encoded.
    assertThat(
            f.repository()
                .queryRange(
                    "sum by (vehicle_id)(increase(wh_vehicle_transitions{to=\"ERROR\"}[5m]))",
                    FROM,
                    TO,
                    "5m"))
        .isEmpty();
    f.server().verify();
  }

  private static final VictoriaMetricsRepository.SeriesData POINT =
      new VictoriaMetricsRepository.SeriesData(
          Map.of("__name__", "wh_vehicle_state", "vehicle_id", "v-1"),
          List.of(1.0, 0.0),
          List.of(1_700_000_000_000L, 1_700_000_300_000L));

  @Test
  void importsBackdatedPointsAsNewlineDelimitedJson() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/import")))
        .andExpect(MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
        .andExpect(
            MockRestRequestMatchers.content()
                .string(org.hamcrest.Matchers.containsString("\"timestamps\"")))
        .andRespond(withSuccess());

    assertThat(f.repository().importSeries(List.of(POINT), 8)).isTrue();
    f.server().verify();
  }

  @Test
  void aRefusedImportIsReportedRatherThanThrown() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/import")))
        .andRespond(withServerError());

    // Seeding demo history is a convenience. Nothing it can do is worth failing a boot over.
    assertThat(f.repository().importSeries(List.of(POINT), 8)).isFalse();
  }

  @Test
  void batchesSoOneImportBodyStaysBounded() {
    Fixture f = fixture();
    for (int i = 0; i < 3; i++) {
      f.server()
          .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/import")))
          .andRespond(withSuccess());
    }

    assertThat(f.repository().importSeries(List.of(POINT, POINT, POINT, POINT, POINT), 2)).isTrue();
    f.server().verify();
  }

  @Test
  void bracketsInTheSeriesMatchParameterAreEncoded() {
    Fixture f = fixture();
    f.server()
        // "match[]" is not legal raw in a query string, and the URI is built already-encoded.
        // Getting this wrong threw IllegalArgumentException and once killed application startup.
        .expect(requestTo(org.hamcrest.Matchers.containsString("match%5B%5D=wh_vehicle_state")))
        .andRespond(
            withSuccess(
                "{\"status\":\"success\",\"data\":[{\"__name__\":\"wh_vehicle_state\"}]}",
                MediaType.APPLICATION_JSON));

    assertThat(f.repository().hasAnySeries("wh_vehicle_state")).isTrue();
    f.server().verify();
  }

  @Test
  void anUnreachableStoreReadsAsNoSeriesRatherThanAnError() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/series")))
        .andRespond(withServerError());

    assertThat(f.repository().hasAnySeries("wh_vehicle_state")).isFalse();
  }

  @Test
  void theProductionConstructorBuildsAClientWithTimeouts() {
    // Port 1 is closed, so this exercises the real RestClient the application uses and proves a
    // transport failure degrades to 503 rather than escaping as a 500.
    VictoriaMetricsRepository real = new VictoriaMetricsRepository("http://127.0.0.1:1");

    assertThatThrownBy(() -> real.queryRange("up", FROM, TO, "1m"))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            t ->
                assertThat(((ResponseStatusException) t).getStatusCode())
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertThat(real.hasAnySeries("wh_vehicle_state")).isFalse();
  }

  @Test
  void aResponseWithoutDataReadsAsNoSeries() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("query_range")))
        .andRespond(withSuccess("{\"status\":\"success\"}", MediaType.APPLICATION_JSON));

    assertThat(f.repository().queryRange("up", FROM, TO, "1m")).isEmpty();
  }

  @Test
  void malformedPointsAreDroppedRatherThanFailingTheWholeSeries() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("query_range")))
        .andRespond(
            withSuccess(
                """
                {"status":"success","data":{"resultType":"matrix","result":[
                  {"metric":{"vehicle_id":"v-1"},
                   "values":[[1755648000,"79"],["bad","point"],[1755648300],[1755648600,"x"]]}
                ]}}
                """,
                MediaType.APPLICATION_JSON));

    // One good point survives; a dashboard gets a short series instead of an error.
    assertThat(f.repository().queryRange("up", FROM, TO, "1m").get(0).points()).hasSize(1);
  }

  @Test
  void anEmptySeriesListMeansNothingHasBeenSeededYet() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/series")))
        .andRespond(
            withSuccess("{\"status\":\"success\",\"data\":[]}", MediaType.APPLICATION_JSON));

    assertThat(f.repository().hasAnySeries("wh_vehicle_state")).isFalse();
  }

  @Test
  void aFailedBatchStopsTheImportInsteadOfPushingTheRest() {
    Fixture f = fixture();
    f.server()
        .expect(requestTo(org.hamcrest.Matchers.containsString("/api/v1/import")))
        .andRespond(withServerError());

    // Only one request is expected: the second batch must not be attempted after the first fails.
    assertThat(f.repository().importSeries(List.of(POINT, POINT, POINT, POINT), 2)).isFalse();
    f.server().verify();
  }

  @Test
  void importingNothingIsASuccessfulNoOp() {
    assertThat(fixture().repository().importSeries(List.of(), 8)).isTrue();
  }
}
