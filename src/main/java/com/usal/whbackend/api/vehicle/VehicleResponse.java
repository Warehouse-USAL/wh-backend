package com.usal.whbackend.api.vehicle;

import java.time.Instant;

public record VehicleResponse(
        String id,
        String name,
        String status,
        double positionX,
        double positionY,
        int battery,
        String currentOrderId,
        Instant lastSeenAt) {}
