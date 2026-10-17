package com.usal.whbackend.api.warehouse.line;

import jakarta.validation.constraints.Min;

public record CreateLineRequest(@Min(1) int numberLine, @Min(1) int maxAllowedPositions) {}
