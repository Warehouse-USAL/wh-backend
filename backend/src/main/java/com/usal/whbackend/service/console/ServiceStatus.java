package com.usal.whbackend.service.console;

/**
 * Reachability of one platform service. {@code state} is {@code UP}, {@code DOWN} or {@code
 * UNKNOWN}; {@code detail} is a short reason (never a URL or stack trace) and {@code uptimeMs} is
 * set for the backend only.
 */
public record ServiceStatus(String name, String state, String detail, Long uptimeMs) {}
