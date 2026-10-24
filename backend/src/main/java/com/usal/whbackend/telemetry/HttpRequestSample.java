package com.usal.whbackend.telemetry;

/**
 * One finished HTTP request. {@code route} is the matched route template (never the raw path), so
 * the tag stays bounded.
 */
public record HttpRequestSample(String route, String method, int status, double durationMs) {}
