package com.usal.whbackend.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OrderStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
