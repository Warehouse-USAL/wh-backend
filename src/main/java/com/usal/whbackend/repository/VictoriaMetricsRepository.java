package com.usal.whbackend.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Reads range queries from VictoriaMetrics.
 *
 * <p>Any transport failure surfaces as {@code 503 METRICS_UNAVAILABLE} rather than a 500: a
 * dashboard must be able to tell "the metrics store is down" from "your query was wrong", and
 * degrade visibly instead of hanging.
 */
@Component
public class VictoriaMetricsRepository {

  private static final Logger log = LoggerFactory.getLogger(VictoriaMetricsRepository.class);

  private final RestClient client;
  private final String baseUrl;

  // @Autowired is required, not decorative: the package-private test-seam constructor below
  // means this class has two constructors, and without this Spring cannot choose one and the
  // context fails to start.
  @Autowired
  public VictoriaMetricsRepository(
      @Value("${victoriametrics.url:http://victoriametrics:8428}") String baseUrl) {
    this(defaultClient(baseUrl), baseUrl);
  }

  /** Test seam: lets a test supply a {@code MockRestServiceServer}-bound client. */
  VictoriaMetricsRepository(RestClient client, String baseUrl) {
    this.client = client;
    this.baseUrl = baseUrl;
  }

  private static RestClient defaultClient(String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  public List<TimeSeries> queryRange(String query, Instant from, Instant to, String step) {
    VmResponse response;
    try {
      response =
          client.get().uri(rangeUri(query, from, to, step)).retrieve().body(VmResponse.class);
    } catch (RestClientException e) {
      log.warn("VictoriaMetrics query failed: {}", e.getMessage());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE");
    }

    if (response == null || response.data() == null || response.data().result() == null) {
      return List.of();
    }
    return response.data().result().stream().map(VictoriaMetricsRepository::toSeries).toList();
  }

  /**
   * Builds the URI without letting Spring's URI-template machinery near it.
   *
   * <p>A MetricsQL selector contains braces — {@code wh_vehicle_state{state="BUSY"}} — and to a
   * {@code UriBuilder} those are a template variable named {@code state="BUSY"}, which it then
   * fails to expand. Every filtered query died that way. So the query value is percent-encoded here
   * and handed over as an already-encoded component: {@code build(true)} skips both the second
   * round of encoding and the expansion.
   */
  private URI rangeUri(String query, Instant from, Instant to, String step) {
    return UriComponentsBuilder.fromUriString(baseUrl)
        .path("/api/v1/query_range")
        .queryParam("query", UriUtils.encodeQueryParam(query, StandardCharsets.UTF_8))
        .queryParam("start", from.getEpochSecond())
        .queryParam("end", to.getEpochSecond())
        .queryParam("step", UriUtils.encodeQueryParam(step, StandardCharsets.UTF_8))
        .build(true)
        .toUri();
  }

  /**
   * Writes backdated points straight into VictoriaMetrics.
   *
   * <p>Used only to seed demo history. The live path publishes through OpenTelemetry, which can
   * only report the present — there is no way to hand the SDK a timestamp from last Tuesday, so a
   * freshly booted stack has no past. Seeding is the one case that needs to write one.
   *
   * @return true when every batch was accepted; false if the store refused or was unreachable,
   *     which is never fatal — demo history is a convenience, not data anyone depends on.
   */
  public boolean importSeries(List<SeriesData> series, int batchSize) {
    ObjectMapper mapper = new ObjectMapper();
    List<SeriesData> batch = new ArrayList<>(batchSize);
    for (SeriesData one : series) {
      batch.add(one);
      if (batch.size() >= batchSize && !postBatch(mapper, batch)) {
        return false;
      }
    }
    return batch.isEmpty() || postBatch(mapper, batch);
  }

  private boolean postBatch(ObjectMapper mapper, List<SeriesData> batch) {
    StringBuilder body = new StringBuilder();
    try {
      for (SeriesData one : batch) {
        body.append(
                mapper.writeValueAsString(
                    Map.of(
                        "metric", one.labels(),
                        "values", one.values(),
                        "timestamps", one.timestampsMs())))
            .append('\n');
      }
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      log.warn("Could not encode demo telemetry: {}", e.getMessage());
      return false;
    }
    batch.clear();

    try {
      client
          .post()
          .uri(
              UriComponentsBuilder.fromUriString(baseUrl)
                  .path("/api/v1/import")
                  .build(true)
                  .toUri())
          .contentType(MediaType.APPLICATION_NDJSON)
          .body(body.toString())
          .retrieve()
          .toBodilessEntity();
      return true;
    } catch (RuntimeException e) {
      log.warn("Could not write demo telemetry to VictoriaMetrics: {}", e.getMessage());
      return false;
    }
  }

  /** Whether any point exists for a series name — used to keep seeding idempotent. */
  public boolean hasAnySeries(String seriesName) {
    try {
      Map<?, ?> response =
          client
              .get()
              .uri(
                  UriComponentsBuilder.fromUriString(baseUrl)
                      .path("/api/v1/series")
                      // The parameter NAME needs encoding too: brackets are not legal raw in a
                      // query string, and build(true) promises the components are already encoded.
                      .queryParam(
                          "match%5B%5D",
                          UriUtils.encodeQueryParam(seriesName, StandardCharsets.UTF_8))
                      .build(true)
                      .toUri())
              .retrieve()
              .body(Map.class);
      return response != null && response.get("data") instanceof List<?> data && !data.isEmpty();
    } catch (RuntimeException e) {
      // Deliberately broad. This only decides whether demo history gets written; nothing it can
      // throw is worth failing over, and a malformed URI here once took application startup with
      // it because the catch was narrower than the things that can go wrong.
      log.warn("Could not check VictoriaMetrics for existing series: {}", e.getMessage());
      return false;
    }
  }

  /** One series of backdated points. Timestamps are epoch milliseconds, as the import API wants. */
  public record SeriesData(
      Map<String, String> labels, List<Double> values, List<Long> timestampsMs) {
    public SeriesData {
      labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
      values = Collections.unmodifiableList(new ArrayList<>(values));
      timestampsMs = Collections.unmodifiableList(new ArrayList<>(timestampsMs));
    }
  }

  private static TimeSeries toSeries(VmResult result) {
    Map<String, String> labels = new LinkedHashMap<>();
    if (result.metric() != null) {
      result
          .metric()
          .forEach(
              (k, v) -> {
                // __name__ is storage bookkeeping, not a dimension the consumer asked for.
                if (!"__name__".equals(k)) {
                  labels.put(k, v);
                }
              });
    }
    List<List<Number>> points =
        result.values() == null
            ? List.of()
            : result.values().stream()
                .map(VictoriaMetricsRepository::toPoint)
                .filter(p -> !p.isEmpty())
                .toList();
    return new TimeSeries(labels, points);
  }

  /** VictoriaMetrics returns each point as {@code [epochSeconds, "value"]} — value is a string. */
  private static List<Number> toPoint(List<Object> raw) {
    if (raw == null || raw.size() < 2) {
      return List.of();
    }
    try {
      long timestamp = ((Number) raw.get(0)).longValue();
      double value = Double.parseDouble(String.valueOf(raw.get(1)));
      return List.of(timestamp, value);
    } catch (ClassCastException | NumberFormatException e) {
      return List.of();
    }
  }

  /** Defensive copies keep the parsed result immutable once it leaves this class. */
  public record TimeSeries(Map<String, String> labels, List<List<Number>> points) {
    public TimeSeries {
      labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
      points = Collections.unmodifiableList(new ArrayList<>(points));
    }
  }

  record VmResponse(String status, VmData data) {}

  record VmData(String resultType, List<VmResult> result) {}

  record VmResult(Map<String, String> metric, List<List<Object>> values) {}
}
