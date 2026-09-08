package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.domain.Zone;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.repository.ZoneRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock PositionRepository positionRepository;
  @Mock LineRepository lineRepository;
  @Mock ZoneRepository zoneRepository;
  @Mock ProductRepository productRepository;
  @InjectMocks PositionService positionService;

  private Line line(String id, String zoneId) {
    Line l = new Line();
    l.setId(id);
    l.setIdZone(zoneId);
    l.setActive(true);
    return l;
  }

  private Position position(String id, String lineId, String zoneId) {
    Position p = new Position();
    p.setId(id);
    p.setIdLine(lineId);
    p.setIdZone(zoneId);
    p.setPositionName("P01");
    p.setMaximumCapacity(100);
    p.setCurrentStock(0);
    p.setSizeStockToSave(StockSize.MEDIO_PALLET);
    p.setActive(false);
    return p;
  }

  @Test
  void getPositionsByLine_lineNotFound_throws() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(LineNotFoundException.class, () -> positionService.getPositionsByLine("bad"));
  }

  @Test
  void getPositionsByLine_returnsPositions() {
    when(lineRepository.findById("l1")).thenReturn(Optional.of(line("l1", "z1")));
    when(positionRepository.findByIdLine("l1")).thenReturn(List.of(position("p1", "l1", "z1")));
    assertEquals(1, positionService.getPositionsByLine("l1").size());
  }

  @Test
  void createPosition_valid_inheritsZoneFromLine() {
    when(lineRepository.findById("l1")).thenReturn(Optional.of(line("l1", "z1")));
    Position saved = position("p1", "l1", "z1");
    when(positionRepository.save(any())).thenReturn(saved);
    Position result =
        positionService.createPosition(
            "l1", new CreatePositionRequest("P01", 100, StockSize.MEDIO_PALLET));
    assertEquals("z1", result.getIdZone());
  }

  @Test
  void updatePosition_assignDifferentProduct_throwsPositionAlreadyOccupied() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    UpdatePositionRequest req =
        new UpdatePositionRequest(null, null, null, null, "product-B", null);
    assertThrows(
        PositionAlreadyOccupiedException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_stockExceedsCapacity_throws() {
    Position p = position("p1", "l1", "z1");
    p.setMaximumCapacity(50);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, 60, null, null, null);
    assertThrows(
        StockExceedsCapacityException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_unassignProduct_clearsStockAndProductId() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    p.setCurrentStock(50);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, null, null, null, true);
    Position result = positionService.updatePosition("p1", req);
    assertNull(result.getProductId());
    assertEquals(0, result.getCurrentStock());
  }

  @Test
  void deletePosition_softDeletes() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    positionService.deletePosition("p1");
    verify(positionRepository).save(argThat(saved -> !((Position) saved).isActive()));
  }

  @Test
  void updatePosition_stockExceedsVolumeCapacity_throws() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-1");
    p.setSizeStockToSave(StockSize.MEDIO_PALLET); // 900,000 cm3
    p.setMaximumCapacity(200);
    p.setCurrentStock(10);

    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(100.0);
    prod.setWidth(100.0);
    prod.setLength(10.0); // Volume = 100,000 cm3 per unit

    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    UpdatePositionRequest req = new UpdatePositionRequest(null, null, 10, null, null, null);

    assertThrows(
        StockExceedsCapacityException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_stockFitsVolumeCapacity_updatesStock() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-1");
    p.setSizeStockToSave(StockSize.MEDIO_PALLET); // 900,000 cm3
    p.setMaximumCapacity(200);
    p.setCurrentStock(5);

    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(100.0);
    prod.setWidth(100.0);
    prod.setLength(10.0); // Volume = 100,000 cm3 per unit

    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    UpdatePositionRequest req = new UpdatePositionRequest(null, null, 5, null, null, null);
    Position result = positionService.updatePosition("p1", req);

    assertEquals(5, result.getCurrentStock());
  }

  private Zone zone(String id, String code) {
    Zone z = new Zone();
    z.setId(id);
    z.setZoneCode(code);
    return z;
  }

  @Test
  void getPosition_notFound_throwsPositionNotFound() {
    when(positionRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(PositionNotFoundException.class, () -> positionService.getPosition("bad"));
  }

  @Test
  void getPosition_found_returnsPosition() {
    when(positionRepository.findById("p1")).thenReturn(Optional.of(position("p1", "l1", "z1")));
    assertEquals("p1", positionService.getPosition("p1").getId());
  }

  @Test
  void createPosition_lineNotFound_throws() {
    when(lineRepository.findById("bad")).thenReturn(Optional.empty());
    CreatePositionRequest req = new CreatePositionRequest("P01", 10, StockSize.CAJA);
    assertThrows(LineNotFoundException.class, () -> positionService.createPosition("bad", req));
  }

  @Test
  void createPosition_inactiveLine_throwsBadRequest() {
    Line l = line("l1", "z1");
    l.setActive(false);
    when(lineRepository.findById("l1")).thenReturn(Optional.of(l));
    CreatePositionRequest req = new CreatePositionRequest("P01", 10, StockSize.CAJA);
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.createPosition("l1", req));
    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
  }

  @Test
  void updatePosition_notFound_throwsPositionNotFound() {
    when(positionRepository.findById("bad")).thenReturn(Optional.empty());
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, null, null, null, null);
    assertThrows(PositionNotFoundException.class, () -> positionService.updatePosition("bad", req));
  }

  @Test
  void updatePosition_unknownProduct_throwsNotFound() {
    Position p = position("p1", "l1", "z1");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.existsById("ghost")).thenReturn(false);
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, null, null, "ghost", null);
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.updatePosition("p1", req));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void updatePosition_negativeStock_throwsStockExceedsCapacity() {
    Position p = position("p1", "l1", "z1");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    UpdatePositionRequest req = new UpdatePositionRequest(null, null, -1, null, null, null);
    assertThrows(
        StockExceedsCapacityException.class, () -> positionService.updatePosition("p1", req));
  }

  @Test
  void updatePosition_assignsNewProductAndRenamesAndActivates() {
    Position p = position("p1", "l1", "z1");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.existsById("product-A")).thenReturn(true);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    UpdatePositionRequest req =
        new UpdatePositionRequest("P99", true, 0, StockSize.PALLET, "product-A", null);
    Position result = positionService.updatePosition("p1", req);

    assertEquals("product-A", result.getProductId());
    assertEquals("P99", result.getPositionName());
    assertTrue(result.isActive());
    assertEquals(StockSize.PALLET, result.getSizeStockToSave());
  }

  @Test
  void updatePosition_sameProductReassigned_isAllowed() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.existsById("product-A")).thenReturn(true);
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    UpdatePositionRequest req =
        new UpdatePositionRequest(null, null, null, null, "product-A", null);
    assertEquals("product-A", positionService.updatePosition("p1", req).getProductId());
  }

  @Test
  void updatePosition_volumeCheckProductMissing_throwsNotFound() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-1");
    p.setSizeStockToSave(StockSize.PALLET);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.empty());

    UpdatePositionRequest req = new UpdatePositionRequest(null, null, 1, null, null, null);
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.updatePosition("p1", req));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void deletePosition_notFound_throwsPositionNotFound() {
    when(positionRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(PositionNotFoundException.class, () -> positionService.deletePosition("bad"));
  }

  // ── Flat listing (GET /warehouse/positions) ────────────────────────────────

  @Test
  void getPositionsFlat_occupiedOnly_excludesInactivePositions() {
    // A soft-deleted position keeps its productId and currentStock (deletePosition only flips
    // isActive), and stock in an inactive position is excluded from available stock everywhere
    // else. The occupied listing must agree, or the dashboard shows a product parked in a
    // position that GET /products reports as holding nothing.
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    p.setCurrentStock(42);
    p.setActive(true);

    when(positionRepository.findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(0))
        .thenReturn(List.of(p));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of(line("l1", "z1")));
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of(zone("z1", "A")));

    var result = positionService.getPositionsFlat(true);

    assertEquals(1, result.size());
    verify(positionRepository, never()).findAll();
  }

  @Test
  void getPositionsFlat_occupiedOnly_queriesOccupiedAndDenormalises() {
    Position p = position("p1", "l1", "z1");
    p.setProductId("product-A");
    p.setCurrentStock(42);
    p.setActive(true); // the occupied finder only ever returns active positions
    Line l = line("l1", "z1");
    l.setNumberLine(3);

    when(positionRepository.findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(0))
        .thenReturn(List.of(p));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of(l));
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of(zone("z1", "A")));

    var result = positionService.getPositionsFlat(true);

    assertEquals(1, result.size());
    assertEquals("p1", result.get(0).idPosition());
    assertEquals("P01", result.get(0).positionName());
    assertEquals("A", result.get(0).zoneCode());
    assertEquals(3, result.get(0).numberLine());
    assertEquals("product-A", result.get(0).productId());
    assertEquals(42, result.get(0).currentStock());
    assertTrue(result.get(0).isActive());
    verify(positionRepository, never()).findAll();
  }

  @Test
  void getPositionsFlat_allPositions_usesFindAll() {
    Position p = position("p1", "l1", "z1");
    when(positionRepository.findAll()).thenReturn(List.of(p));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of(line("l1", "z1")));
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of(zone("z1", "A")));

    var result = positionService.getPositionsFlat(false);

    assertEquals(1, result.size());
    assertNull(result.get(0).productId());
    assertFalse(result.get(0).isActive());
    verify(positionRepository, never())
        .findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(anyInt());
  }

  @Test
  void getPositionsFlat_empty_shortCircuitsWithoutLookups() {
    when(positionRepository.findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(0))
        .thenReturn(List.of());
    assertTrue(positionService.getPositionsFlat(true).isEmpty());
    verifyNoInteractions(zoneRepository);
  }

  @Test
  void getPositionsFlat_danglingLineAndZone_fallsBackToDefaults() {
    Position p = position("p1", "l1", "z1");
    when(positionRepository.findAll()).thenReturn(List.of(p));
    when(lineRepository.findAllById(List.of("l1"))).thenReturn(List.of());
    when(zoneRepository.findAllById(List.of("z1"))).thenReturn(List.of());

    var result = positionService.getPositionsFlat(false);

    assertEquals(0, result.get(0).numberLine());
    assertNull(result.get(0).zoneCode());
  }

  @Test
  void getPositionsFlat_nullLineAndZoneIds_areSkippedInLookup() {
    Position p = position("p1", null, null);
    when(positionRepository.findAll()).thenReturn(List.of(p));
    when(lineRepository.findAllById(List.of())).thenReturn(List.of());
    when(zoneRepository.findAllById(List.of())).thenReturn(List.of());

    var result = positionService.getPositionsFlat(false);

    assertEquals(1, result.size());
    assertNull(result.get(0).zoneCode());
  }

  // ── increaseStock (restock reception support) ──────────────────────────────

  @Test
  void increaseStock_positionNotFound_throws() {
    when(positionRepository.findById("bad")).thenReturn(Optional.empty());
    assertThrows(
        PositionNotFoundException.class,
        () -> positionService.increaseStock("bad", "product-1", 10));
  }

  @Test
  void increaseStock_inactivePosition_throwsBadRequest() {
    Position p = position("p1", "l1", "z1");
    p.setActive(false);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.increaseStock("p1", "product-1", 10));
    assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, ex.getStatusCode());
    assertEquals("POSITION_INACTIVE", ex.getReason());
  }

  @Test
  void increaseStock_differentProductAlreadyAssigned_throwsPositionAlreadyOccupied() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setProductId("product-A");
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    assertThrows(
        PositionAlreadyOccupiedException.class,
        () -> positionService.increaseStock("p1", "product-B", 10));
  }

  @Test
  void increaseStock_exceedsNumericCapacity_throws() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setMaximumCapacity(20);
    p.setCurrentStock(15);
    p.setSizeStockToSave(null);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    assertThrows(
        StockExceedsCapacityException.class,
        () -> positionService.increaseStock("p1", "product-1", 10));
  }

  @Test
  void increaseStock_exceedsVolumeCapacity_throws() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setMaximumCapacity(1000);
    p.setCurrentStock(0);
    p.setSizeStockToSave(StockSize.CAJA); // 48,000 cm3

    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0); // 1,000 cm3 per unit -> max 48 units

    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    assertThrows(
        StockExceedsCapacityException.class,
        () -> positionService.increaseStock("p1", "product-1", 50));
  }

  @Test
  void increaseStock_emptyPosition_assignsProductAndIncrements() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setCurrentStock(0);
    p.setSizeStockToSave(null);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Position result = positionService.increaseStock("p1", "product-1", 10);

    assertEquals("product-1", result.getProductId());
    assertEquals(10, result.getCurrentStock());
  }

  @Test
  void increaseStock_samePositionTwice_accumulatesStock() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setProductId("product-1");
    p.setCurrentStock(10);
    p.setSizeStockToSave(null);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Position result = positionService.increaseStock("p1", "product-1", 5);

    assertEquals(15, result.getCurrentStock());
  }

  // ── getAvailablePositions (GET /warehouse/positions/available) ─────────────

  @Test
  void getAvailablePositions_invalidQuantity_throws() {
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.getAvailablePositions("product-1", StockSize.PALLET, 0));
    assertEquals("INVALID_QUANTITY", ex.getReason());
    verifyNoInteractions(productRepository);
  }

  @Test
  void getAvailablePositions_productNotFound_throws() {
    when(productRepository.findById("ghost")).thenReturn(Optional.empty());
    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.getAvailablePositions("ghost", StockSize.PALLET, 10));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
  }

  @Test
  void getAvailablePositions_productInactive_throwsNotFound() {
    com.usal.whbackend.domain.Product inactive = new com.usal.whbackend.domain.Product();
    inactive.setId("product-1");
    inactive.setActive(false);
    when(productRepository.findById("product-1")).thenReturn(Optional.of(inactive));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> positionService.getAvailablePositions("product-1", StockSize.PALLET, 10));
  }

  @Test
  void getAvailablePositions_filtersOccupiedByOtherProductAndSortsByCapacityDesc() {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setActive(true);
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0); // 1,000 cm3/unit
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    Position roomy = position("p1", "l1", "z1"); // empty, maxCapacity 100
    roomy.setSizeStockToSave(StockSize.PALLET); // 1,800,000 cm3 -> 1800 units by volume
    roomy.setMaximumCapacity(100);
    roomy.setCurrentStock(0);

    Position tight = position("p2", "l1", "z1");
    tight.setSizeStockToSave(StockSize.PALLET);
    tight.setMaximumCapacity(20);
    tight.setCurrentStock(15);

    Position occupiedByOther = position("p3", "l1", "z1");
    occupiedByOther.setSizeStockToSave(StockSize.PALLET);
    occupiedByOther.setProductId("other-product");
    occupiedByOther.setMaximumCapacity(100);
    occupiedByOther.setCurrentStock(0);

    when(positionRepository.findByIsActiveTrueAndSizeStockToSave(StockSize.PALLET))
        .thenReturn(List.of(tight, roomy, occupiedByOther));

    var result = positionService.getAvailablePositions("product-1", StockSize.PALLET, 10);

    assertEquals(2, result.size());
    assertEquals("p1", result.get(0).positionId());
    assertEquals(100, result.get(0).availableUnits());
    assertEquals("p2", result.get(1).positionId());
    assertEquals(5, result.get(1).availableUnits());
  }

  @Test
  void getAvailablePositions_zeroAvailableCapacity_excluded() {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setActive(true);
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0);
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    Position full = position("p1", "l1", "z1");
    full.setSizeStockToSave(StockSize.PALLET);
    full.setMaximumCapacity(10);
    full.setCurrentStock(10);

    when(positionRepository.findByIsActiveTrueAndSizeStockToSave(StockSize.PALLET))
        .thenReturn(List.of(full));

    assertTrue(positionService.getAvailablePositions("product-1", StockSize.PALLET, 10).isEmpty());
  }

  @Test
  void getAvailablePositions_alreadyHoldsSameProduct_isIncluded() {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setActive(true);
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0);
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    Position sameProduct = position("p1", "l1", "z1");
    sameProduct.setSizeStockToSave(StockSize.PALLET);
    sameProduct.setProductId("product-1");
    sameProduct.setMaximumCapacity(100);
    sameProduct.setCurrentStock(10);

    when(positionRepository.findByIsActiveTrueAndSizeStockToSave(StockSize.PALLET))
        .thenReturn(List.of(sameProduct));

    var result = positionService.getAvailablePositions("product-1", StockSize.PALLET, 1);

    assertEquals(1, result.size());
    assertEquals("p1", result.get(0).positionId());
  }

  @Test
  void getAvailablePositions_zeroVolumeProduct_capacityBoundedByCountOnly() {
    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setActive(true);
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));

    Position pos = position("p1", "l1", "z1");
    pos.setSizeStockToSave(StockSize.PALLET);
    pos.setMaximumCapacity(30);
    pos.setCurrentStock(5);

    when(positionRepository.findByIsActiveTrueAndSizeStockToSave(StockSize.PALLET))
        .thenReturn(List.of(pos));

    var result = positionService.getAvailablePositions("product-1", StockSize.PALLET, 1);

    assertEquals(1, result.size());
    assertEquals(25, result.get(0).availableUnits());
  }

  // ── increaseStock: volume-check branches ────────────────────────────────────

  @Test
  void increaseStock_volumeCheckProductMissing_throwsNotFound() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setSizeStockToSave(StockSize.PALLET);
    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.empty());

    var ex =
        assertThrows(
            org.springframework.web.server.ResponseStatusException.class,
            () -> positionService.increaseStock("p1", "product-1", 5));
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, ex.getStatusCode());
    assertEquals("PRODUCT_NOT_FOUND", ex.getReason());
  }

  @Test
  void increaseStock_fitsWithinVolumeCapacity_succeeds() {
    Position p = position("p1", "l1", "z1");
    p.setActive(true);
    p.setMaximumCapacity(1000);
    p.setCurrentStock(0);
    p.setSizeStockToSave(StockSize.PALLET); // 1,800,000 cm3

    com.usal.whbackend.domain.Product prod = new com.usal.whbackend.domain.Product();
    prod.setId("product-1");
    prod.setHeight(10.0);
    prod.setWidth(10.0);
    prod.setLength(10.0); // 1,000 cm3/unit -> 5,000 cm3 for 5 units, well within budget

    when(positionRepository.findById("p1")).thenReturn(Optional.of(p));
    when(productRepository.findById("product-1")).thenReturn(Optional.of(prod));
    when(positionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    Position result = positionService.increaseStock("p1", "product-1", 5);

    assertEquals(5, result.getCurrentStock());
  }
}
