package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.StockSize;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
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
}
