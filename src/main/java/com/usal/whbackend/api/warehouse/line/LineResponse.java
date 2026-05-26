package com.usal.whbackend.api.warehouse.line;

import com.usal.whbackend.domain.Line;
import java.time.Instant;

public record LineResponse(
    String idLine,
    String idZone,
    int numberLine,
    boolean isActive,
    int maxAllowedPositions,
    Instant createdAt) {

  public static LineResponse from(Line line) {
    return new LineResponse(
        line.getId(),
        line.getIdZone(),
        line.getNumberLine(),
        line.isActive(),
        line.getMaxAllowedPositions(),
        line.getCreatedAt());
  }
}
