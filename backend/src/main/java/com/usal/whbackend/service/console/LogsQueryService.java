package com.usal.whbackend.service.console;

import com.usal.whbackend.repository.VictoriaLogsRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Validates the five fixed log filters, applies defaults and caps, and queries the log store. */
@Service
public class LogsQueryService {

  /** The services that ship logs. Kept here, in one place. */
  static final Set<String> SERVICES = Set.of("wh-backend", "wh-bridge", "restock-cron");

  static final Set<String> LEVELS = Set.of("debug", "info", "warn", "error");

  static final int DEFAULT_LIMIT = 100;
  static final int MAX_LIMIT = 500;
  static final int MAX_TEXT_LENGTH = 200;
  static final Duration MAX_RANGE = Duration.ofDays(7);
  static final Duration DEFAULT_RANGE = Duration.ofHours(1);

  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
  private static final Pattern FORBIDDEN_TEXT = Pattern.compile("[\"{}|\\\\`\\r\\n]");

  private final VictoriaLogsRepository repository;

  public LogsQueryService(VictoriaLogsRepository repository) {
    this.repository = repository;
  }

  public List<LogLine> query(LogsQuery query) {
    String service = blankToNull(query.service());
    String level = blankToNull(query.level());
    String requestId = blankToNull(query.requestId());
    String text = blankToNull(query.text());

    if (service != null && !SERVICES.contains(service)) {
      throw invalid();
    }
    if (level != null && !LEVELS.contains(level)) {
      throw invalid();
    }
    if (requestId != null && !REQUEST_ID.matcher(requestId).matches()) {
      throw invalid();
    }
    if (text != null && (text.length() > MAX_TEXT_LENGTH || FORBIDDEN_TEXT.matcher(text).find())) {
      throw invalid();
    }

    Instant to = query.to() != null ? query.to() : Instant.now();
    Instant from = query.from() != null ? query.from() : to.minus(DEFAULT_RANGE);
    if (from.isAfter(to) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
    }

    int limit =
        query.limit() == null || query.limit() <= 0
            ? DEFAULT_LIMIT
            : Math.min(query.limit(), MAX_LIMIT);
    return repository.search(service, level, requestId, text, from, to, limit);
  }

  private static ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
