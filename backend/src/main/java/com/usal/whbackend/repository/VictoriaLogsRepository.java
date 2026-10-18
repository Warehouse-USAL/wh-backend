package com.usal.whbackend.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.service.console.LogLine;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Reads log lines from VictoriaLogs (v1.x) through {@code /select/logsql/query}.
 *
 * <p>Every VictoriaLogs-specific name and piece of LogsQL syntax lives in this class. The field
 * names below depend on how VictoriaLogs maps OTLP resource and log attributes (dots kept as-is or
 * turned into underscores, depending on version) and could not be checked against a real store when
 * this was written. They are verified end to end in Task 15 Step 4b / Task 28 and may need
 * adjusting there; nothing outside this class should need to change.
 *
 * <p>The LogsQL is assembled from values the service has already validated; client input never
 * reaches it unchecked. Any transport failure surfaces as {@code 502 LOG_STORE_UNAVAILABLE}.
 */
@Component
public class VictoriaLogsRepository {

  private static final Logger log = LoggerFactory.getLogger(VictoriaLogsRepository.class);

  /** OTLP resource attribute {@code service.name}. */
  static final String FIELD_SERVICE = "service.name";

  /** OTLP severity text. */
  static final String FIELD_LEVEL = "severity";

  /** Log attribute set from the MDC key {@code request_id}. */
  static final String FIELD_REQUEST_ID = "request_id";

  /** VictoriaLogs built-in message field. */
  static final String FIELD_MESSAGE = "_msg";

  /** VictoriaLogs built-in timestamp field. */
  static final String FIELD_TIME = "_time";

  /**
   * API service names mapped to the store's {@code service.name} values (a Task 15 / 28
   * verification point like the field names above). The API never exposes the {@code wh-} names.
   */
  private static final Map<String, String> SERVICE_NAMES =
      Map.of("backend", "wh-backend", "bridge", "wh-bridge");

  private static final String QUERY_PATH = "/select/logsql/query";
  private static final String MATCH_ALL = "*";
  private static final String NEWEST_FIRST = " | sort by (" + FIELD_TIME + ") desc";

  private final RestClient client;
  private final String baseUrl;
  private final ObjectMapper mapper = new ObjectMapper();

  @Autowired
  public VictoriaLogsRepository(ConsoleProperties properties) {
    this(defaultClient(properties.getVictorialogsUrl()), properties.getVictorialogsUrl());
  }

  /** Test seam: lets a test supply a {@code MockRestServiceServer}-bound client. */
  VictoriaLogsRepository(RestClient client, String baseUrl) {
    this.client = client;
    this.baseUrl = baseUrl;
  }

  private static RestClient defaultClient(String baseUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(2));
    factory.setReadTimeout(Duration.ofSeconds(10));
    return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
  }

  /**
   * Builds the LogsQL for the fixed filters, newest first; null or blank filters are skipped.
   * Callers must have validated each value (no quotes, braces, pipes, backslashes, backticks or
   * newlines).
   */
  static String buildQuery(String service, String level, String requestId, String text) {
    List<String> parts = new ArrayList<>();
    if (service != null) {
      parts.add(
          quoted(FIELD_SERVICE) + ":=" + quoted(SERVICE_NAMES.getOrDefault(service, service)));
    }
    if (level != null) {
      parts.add(quoted(FIELD_LEVEL) + ":i(" + quoted(level) + ")");
    }
    if (requestId != null) {
      parts.add(quoted(FIELD_REQUEST_ID) + ":=" + quoted(requestId));
    }
    if (text != null) {
      parts.add(quoted(text));
    }
    String filters = parts.isEmpty() ? MATCH_ALL : String.join(" ", parts);
    return filters + NEWEST_FIRST;
  }

  private static String quoted(String value) {
    return '"' + value + '"';
  }

  public List<LogLine> search(
      String service,
      String level,
      String requestId,
      String text,
      Instant from,
      Instant to,
      int limit) {
    String body;
    try {
      body =
          client
              .get()
              .uri(uri(buildQuery(service, level, requestId, text), from, to, limit))
              .retrieve()
              .body(String.class);
    } catch (RestClientException e) {
      log.warn("VictoriaLogs query failed: {}", e.getMessage());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "LOG_STORE_UNAVAILABLE");
    }
    return parse(body);
  }

  /** Same already-encoded URI approach as the metrics repository: LogsQL contains braces. */
  private URI uri(String query, Instant from, Instant to, int limit) {
    return UriComponentsBuilder.fromUriString(baseUrl)
        .path(QUERY_PATH)
        .queryParam("query", strictlyEncoded(query))
        .queryParam("start", from.toString())
        .queryParam("end", to.toString())
        .queryParam("limit", limit)
        .build(true)
        .toUri();
  }

  /**
   * Percent-encodes everything but unreserved characters. {@code UriUtils.encodeQueryParam} leaves
   * {@code +} and {@code ;} alone, which the server then reads as a space or a parameter separator.
   */
  private static String strictlyEncoded(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  /** Newline-delimited JSON; blank and malformed lines are skipped, missing fields are null. */
  private List<LogLine> parse(String body) {
    List<LogLine> lines = new ArrayList<>();
    if (body == null) {
      return lines;
    }
    for (String raw : body.split("\n")) {
      if (raw.isBlank()) {
        continue;
      }
      try {
        JsonNode node = mapper.readTree(raw);
        if (node != null && node.isObject()) {
          lines.add(
              new LogLine(
                  time(text(node, FIELD_TIME)),
                  text(node, FIELD_LEVEL),
                  apiService(text(node, FIELD_SERVICE)),
                  text(node, FIELD_MESSAGE),
                  text(node, FIELD_REQUEST_ID)));
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        log.debug("Skipping malformed VictoriaLogs line");
      }
    }
    return lines;
  }

  private static String apiService(String storeName) {
    return SERVICE_NAMES.entrySet().stream()
        .filter(e -> e.getValue().equals(storeName))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(storeName);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Instant time(String value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException e) {
      return null;
    }
  }
}
