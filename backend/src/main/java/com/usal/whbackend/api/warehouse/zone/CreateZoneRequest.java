package com.usal.whbackend.api.warehouse.zone;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateZoneRequest(@NotBlank String zoneCode, @Min(1) int maxAllowedLines) {}
