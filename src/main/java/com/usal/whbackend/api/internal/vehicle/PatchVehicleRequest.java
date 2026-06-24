package com.usal.whbackend.api.internal.vehicle;

import java.time.Instant;

public record PatchVehicleRequest(
    String status,
    Double positionX,
    Double positionY,
    Integer battery,
    String currentOrderId,
    Instant lastSeenAt) {}
