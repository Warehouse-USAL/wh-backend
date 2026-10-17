package com.usal.whbackend.api.warehouse.line;

import jakarta.validation.constraints.Min;

public record UpdateLineRequest(
    @Min(1) Integer numberLine, @Min(1) Integer maxAllowedPositions, Boolean isActive) {}
