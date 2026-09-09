package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.service.PositionService.AvailablePosition;

public record AvailablePositionResponse(
    String positionId, String positionName, int availableUnits) {

  public static AvailablePositionResponse from(AvailablePosition p) {
    return new AvailablePositionResponse(p.positionId(), p.positionName(), p.availableUnits());
  }
}
