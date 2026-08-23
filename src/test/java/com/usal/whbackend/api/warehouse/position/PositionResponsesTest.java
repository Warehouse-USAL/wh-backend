package com.usal.whbackend.api.warehouse.position;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.Zone;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PositionResponsesTest {

  private Position position() {
    Position p = new Position();
    p.setId("p1");
    p.setIdLine("l1");
    p.setIdZone("z1");
    p.setPositionName("P01");
    p.setActive(true);
    p.setMaximumCapacity(100);
    p.setSizeStockToSave(StockSize.PALLET);
    p.setProductId("prod-1");
    p.setCurrentStock(42);
    p.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return p;
  }

  @Test
  void detailResponse_withProduct_mapsAssignedProduct() {
    Product product = new Product();
    product.setId("prod-1");
    product.setSku("SKU-1");
    product.setName("Widget");

    PositionDetailResponse r = PositionDetailResponse.from(position(), product);

    assertThat(r.idPosition()).isEqualTo("p1");
    assertThat(r.idLine()).isEqualTo("l1");
    assertThat(r.idZone()).isEqualTo("z1");
    assertThat(r.positionName()).isEqualTo("P01");
    assertThat(r.isActive()).isTrue();
    assertThat(r.maximumCapacity()).isEqualTo(100);
    assertThat(r.sizeStockToSave()).isEqualTo(StockSize.PALLET);
    assertThat(r.productId()).isEqualTo("prod-1");
    assertThat(r.currentStock()).isEqualTo(42);
    assertThat(r.createdAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(r.assignedProduct())
        .isEqualTo(new PositionDetailResponse.AssignedProduct("prod-1", "SKU-1", "Widget"));
    assertThat(r.assignedProduct().toString()).contains("Widget");
    assertThat(r.assignedProduct().hashCode())
        .isEqualTo(
            new PositionDetailResponse.AssignedProduct("prod-1", "SKU-1", "Widget").hashCode());
  }

  @Test
  void detailResponse_withoutProduct_leavesAssignedProductNull() {
    PositionDetailResponse r = PositionDetailResponse.from(position(), null);

    assertThat(r.assignedProduct()).isNull();
    assertThat(r).isEqualTo(PositionDetailResponse.from(position(), null));
    assertThat(r.toString()).contains("P01");
  }

  @Test
  void summaryResponse_denormalisesZoneAndLine() {
    Line line = new Line();
    line.setId("l1");
    line.setNumberLine(3);
    Zone zone = new Zone();
    zone.setId("z1");
    zone.setZoneCode("A");

    PositionSummaryResponse r = PositionSummaryResponse.from(position(), line, zone);

    assertThat(r).isEqualTo(new PositionSummaryResponse("p1", "P01", "A", 3, "prod-1", 42));
    assertThat(r.hashCode())
        .isEqualTo(new PositionSummaryResponse("p1", "P01", "A", 3, "prod-1", 42).hashCode());
    assertThat(r.toString()).contains("P01");
  }

  @Test
  void summaryResponse_missingZoneAndLine_fallsBackToDefaults() {
    PositionSummaryResponse r = PositionSummaryResponse.from(position(), null, null);

    assertThat(r.zoneCode()).isNull();
    assertThat(r.numberLine()).isZero();
    assertThat(r.idPosition()).isEqualTo("p1");
    assertThat(r.currentStock()).isEqualTo(42);
  }

  @Test
  void fitValidationResponse_exposesAllComponents() {
    FitValidationResponse r = new FitValidationResponse(true, 1000.0, 48000.0, 2000.0, 48);

    assertThat(r.fits()).isTrue();
    assertThat(r.productVolume()).isEqualTo(1000.0);
    assertThat(r.containerVolume()).isEqualTo(48000.0);
    assertThat(r.requiredVolume()).isEqualTo(2000.0);
    assertThat(r.maxQuantityAllowed()).isEqualTo(48);
  }

  @Test
  void requestRecords_exposeComponents() {
    CreatePositionRequest create = new CreatePositionRequest("P01", 10, StockSize.CAJA);
    assertThat(create.positionName()).isEqualTo("P01");
    assertThat(create.maximumCapacity()).isEqualTo(10);
    assertThat(create.sizeStockToSave()).isEqualTo(StockSize.CAJA);

    UpdatePositionRequest update =
        new UpdatePositionRequest("P02", true, 5, StockSize.PALLET, "prod-1", false);
    assertThat(update.positionName()).isEqualTo("P02");
    assertThat(update.isActive()).isTrue();
    assertThat(update.currentStock()).isEqualTo(5);
    assertThat(update.sizeStockToSave()).isEqualTo(StockSize.PALLET);
    assertThat(update.productId()).isEqualTo("prod-1");
    assertThat(update.unassignProduct()).isFalse();

    ValidateFitRequest fit = new ValidateFitRequest("prod-1", 2, StockSize.CAJA);
    assertThat(fit.productId()).isEqualTo("prod-1");
    assertThat(fit.quantity()).isEqualTo(2);
    assertThat(fit.size()).isEqualTo(StockSize.CAJA);
  }
}
