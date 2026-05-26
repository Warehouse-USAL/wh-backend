package com.usal.whbackend.api.warehouse.zone;

import com.usal.whbackend.domain.Zone;
import java.time.Instant;

public record ZoneResponse(
    String idZone, String zoneCode, boolean isActive, int maxAllowedLines, Instant createdAt) {

  public static ZoneResponse from(Zone zone) {
    return new ZoneResponse(
        zone.getId(),
        zone.getZoneCode(),
        zone.isActive(),
        zone.getMaxAllowedLines(),
        zone.getCreatedAt());
  }
}
