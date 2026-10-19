package com.usal.whbackend.service.console;

import java.time.Instant;

/**
 * A log search over five fixed filters. Every field is optional; {@code from}/{@code to} default to
 * the last hour and {@code limit} to 100 (capped at 500). Never raw LogsQL.
 */
public record LogsQuery(
    String service,
    String level,
    String requestId,
    String text,
    Instant from,
    Instant to,
    Integer limit) {}
