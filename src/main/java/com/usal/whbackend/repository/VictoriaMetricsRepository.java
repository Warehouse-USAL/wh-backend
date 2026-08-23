package com.usal.whbackend.repository;

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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

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

  // @Autowired is required, not decorative: the package-private test-seam constructor below
  // means this class has two constructors, and without this Spring cannot choose one and the
  // context fails to start.
  @Autowired
  public VictoriaMetricsRepository(
      @Value("${victoriametrics.url:http://victoriametrics:8428}") String baseUrl) {
    this(defaultClient(baseUrl));
  }

  /** Test seam: lets a test supply a {@code MockRestServiceServer}-bound client. */
  VictoriaMetricsRepository(RestClient client) {
    this.client = client;
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
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/api/v1/query_range")
                          .queryParam("query", query)
                          .queryParam("start", from.getEpochSecond())
                          .queryParam("end", to.getEpochSecond())
                          .queryParam("step", step)
                          .build())
              .retrieve()
              .body(VmResponse.class);
    } catch (RestClientException e) {
      log.warn("VictoriaMetrics query failed: {}", e.getMessage());
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "METRICS_UNAVAILABLE");
    }

    if (response == null || response.data() == null || response.data().result() == null) {
      return List.of();
    }
    return response.data().result().stream().map(VictoriaMetricsRepository::toSeries).toList();
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
