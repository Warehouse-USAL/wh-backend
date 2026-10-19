package com.usal.whbackend.service.console;

import java.time.Instant;

/** One log line as shown in the console. Any field the store did not return is null. */
public record LogLine(
    Instant time, String level, String service, String message, String requestId) {}
