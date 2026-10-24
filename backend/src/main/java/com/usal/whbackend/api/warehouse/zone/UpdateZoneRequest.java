package com.usal.whbackend.api.warehouse.zone;

import jakarta.validation.constraints.Min;

public record UpdateZoneRequest(
    String zoneCode, @Min(1) Integer maxAllowedLines, Boolean isActive) {}
