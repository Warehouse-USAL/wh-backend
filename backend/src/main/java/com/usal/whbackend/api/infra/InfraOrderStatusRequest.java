package com.usal.whbackend.api.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Order status change reported by Infra. {@code vehicleId} is required for {@code in_progress}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InfraOrderStatusRequest(String status, String vehicleId, String timestamp) {}
