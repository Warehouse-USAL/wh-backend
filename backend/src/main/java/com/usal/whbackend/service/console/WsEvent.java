package com.usal.whbackend.service.console;

import java.time.Instant;

/**
 * One WebSocket lifecycle event shown in the console. {@code kind} is {@code
 * connect|push|close|reject}; {@code detail} is a short summary, never a payload, token or URL
 * query.
 */
public record WsEvent(Instant at, String kind, String detail) {}
