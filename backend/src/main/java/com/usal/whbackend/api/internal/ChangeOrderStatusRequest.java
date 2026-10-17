package com.usal.whbackend.api.internal;

import java.time.Instant;

/** {@code completedAt} is optional and only honoured when completing an order. */
public record ChangeOrderStatusRequest(String status, Instant completedAt) {}
