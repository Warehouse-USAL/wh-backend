package com.usal.whbackend.config;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.service.PositionService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The physical warehouse: ONE zone with ONE line and SIX positions, PM1, PM2, PM3 and PA1, PA2,
 * PA3. Those names are the QR codes the Infra vehicle recognises; on the server the printed texts
 * are the position name behind a prefix ({@code WH2|PA1}, ... — {@code WH_QR_PREFIX}).
 *
 * <p>Shared by {@link DataInitializer} (the structure every deployment gets, positions empty) and
 * {@link DemoDataset} (the same structure, now with products and stock). Ids are deterministic so
 * the demo seed replaces the bootstrap documents instead of duplicating them.
 */
final class WarehouseLayout {

  static final String ZONE_ID = "z-A";
  static final String ZONE_CODE = "A";
  static final String LINE_ID = "l-A-1";
  static final int LINE_NUMBER = 1;

  // A position never holds more than this many units, however small the product is.
  private static final int POSITION_MAX_CAPACITY = 1_500;
  // Capacity of a position nobody has assigned a product to yet, by container size.
  private static final Map<StockSize, Integer> EMPTY_POSITION_CAPACITY =
      Map.of(StockSize.CAJA, 100, StockSize.MEDIO_PALLET, 300, StockSize.PALLET, 500);

  /**
   * A position and what the demo stocks in it: {@code sku} is the product the demo puts there (null
   * = left empty) and {@code size} the container the position is sized for.
   */
  record Slot(String name, String sku, StockSize size) {}

  /**
   * Layout order is the demo's catalogue order of stocked products: the first one is the
   * intentionally low-stock, never-ordered new arrival.
   */
  static final List<Slot> SLOTS =
      List.of(
          new Slot("PA1", "TEC-001", StockSize.CAJA),
          new Slot("PM3", "TEC-005", StockSize.PALLET),
          new Slot("PA2", "TEC-002", StockSize.MEDIO_PALLET),
          new Slot("PM1", "TEC-009", StockSize.MEDIO_PALLET),
          new Slot("PA3", "TEC-004", StockSize.MEDIO_PALLET),
          new Slot("PM2", "TEC-007", StockSize.MEDIO_PALLET));

  private WarehouseLayout() {}

  static String positionId(String name) {
    return "pos-" + LINE_ID + "-" + name;
  }

  static Zone zone(Instant createdAt) {
    Zone z = new Zone();
    z.setId(ZONE_ID);
    z.setZoneCode(ZONE_CODE);
    z.setMaxAllowedLines(1);
    z.setActive(true);
    z.setCreatedAt(createdAt);
    return z;
  }

  static Line line(Instant createdAt) {
    Line l = new Line();
    l.setId(LINE_ID);
    l.setIdZone(ZONE_ID);
    l.setNumberLine(LINE_NUMBER);
    l.setMaxAllowedPositions(6);
    l.setActive(true);
    l.setCreatedAt(createdAt);
    return l;
  }

  /** A position of the layout, holding {@code product} (null = empty) with no stock yet. */
  static Position position(Slot slot, Product product, String qrPrefix, Instant createdAt) {
    Position p = new Position();
    p.setId(positionId(slot.name()));
    p.setIdLine(LINE_ID);
    p.setIdZone(ZONE_ID);
    p.setPositionName(slot.name());
    p.setQrCode(qrPrefix + slot.name());
    p.setActive(true);
    p.setSizeStockToSave(slot.size());
    p.setMaximumCapacity(maximumCapacity(product, slot.size()));
    p.setProductId(product == null ? null : product.getId());
    p.setCurrentStock(0);
    p.setCreatedAt(createdAt);
    return p;
  }

  /**
   * The most units of {@code product} a {@code size} container takes by volume — the same rule
   * {@code PositionService.updatePosition} enforces — capped at {@link #POSITION_MAX_CAPACITY}, so
   * no stock the simulation puts away can ever be rejected when the position is edited later.
   */
  static int maximumCapacity(Product product, StockSize size) {
    if (product == null) {
      return EMPTY_POSITION_CAPACITY.get(size);
    }
    int byVolume = PositionService.maxUnitsByVolume(product.getVolume(), size.getVolumeCm3());
    int capacity = Math.min(POSITION_MAX_CAPACITY, byVolume);
    // Guard against floating-point rounding right at the boundary.
    while (capacity > 1 && capacity * product.getVolume() > size.getVolumeCm3()) {
      capacity--;
    }
    return capacity;
  }
}
