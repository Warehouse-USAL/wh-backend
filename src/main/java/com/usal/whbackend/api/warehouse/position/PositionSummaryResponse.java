package com.usal.whbackend.api.warehouse.position;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Zone;

/**
 * Flat view of a position, denormalised with its zone code and line number so the dashboard can
 * resolve {@code product_id → position} in a single call instead of walking the zone → line →
 * position hierarchy.
 *
 * <p>{@code isActive} is carried through because the unfiltered listing includes soft-deleted
 * positions, whose stock is excluded from the available-stock computations.
 */
public record PositionSummaryResponse(
    String idPosition,
    String positionName,
    String zoneCode,
    int numberLine,
    String productId,
    int currentStock,
    boolean isActive) {

  public static PositionSummaryResponse from(Position p, Line line, Zone zone) {
    return new PositionSummaryResponse(
        p.getId(),
        p.getPositionName(),
        zone != null ? zone.getZoneCode() : null,
        line != null ? line.getNumberLine() : 0,
        p.getProductId(),
        p.getCurrentStock(),
        p.isActive());
  }
}
