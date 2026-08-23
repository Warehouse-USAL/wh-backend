package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.PositionSummaryResponse;
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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PositionService {

  private final PositionRepository positionRepository;
  private final LineRepository lineRepository;
  private final ZoneRepository zoneRepository;
  private final ProductRepository productRepository;

  public PositionService(
      PositionRepository positionRepository,
      LineRepository lineRepository,
      ZoneRepository zoneRepository,
      ProductRepository productRepository) {
    this.positionRepository = positionRepository;
    this.lineRepository = lineRepository;
    this.zoneRepository = zoneRepository;
    this.productRepository = productRepository;
  }

  public List<Position> getPositionsByLine(String lineId) {
    lineRepository.findById(lineId).orElseThrow(() -> new LineNotFoundException(lineId));
    return positionRepository.findByIdLine(lineId);
  }

  /**
   * Flat listing of every position, denormalised with zone code and line number. {@code
   * occupiedOnly} narrows the payload to active positions that hold stock of an assigned product —
   * the same set the stock computations count, so the dashboard's position column agrees with the
   * available stock reported by {@code GET /products}. The unfiltered listing returns inactive
   * positions too; callers tell them apart via {@code is_active}.
   */
  public List<PositionSummaryResponse> getPositionsFlat(boolean occupiedOnly) {
    List<Position> positions =
        occupiedOnly
            ? positionRepository.findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(0)
            : positionRepository.findAll();
    if (positions.isEmpty()) {
      return List.of();
    }

    // Bulk-fetch lines and zones — 2 queries instead of 2 × N.
    Map<String, Line> lineMap =
        lineRepository
            .findAllById(
                positions.stream()
                    .map(Position::getIdLine)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(Line::getId, Function.identity()));
    Map<String, Zone> zoneMap =
        zoneRepository
            .findAllById(
                positions.stream()
                    .map(Position::getIdZone)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList())
            .stream()
            .collect(Collectors.toMap(Zone::getId, Function.identity()));

    return positions.stream()
        .map(
            p ->
                PositionSummaryResponse.from(
                    p, lineMap.get(p.getIdLine()), zoneMap.get(p.getIdZone())))
        .toList();
  }

  public Position getPosition(String id) {
    return positionRepository.findById(id).orElseThrow(() -> new PositionNotFoundException(id));
  }

  public Position createPosition(String lineId, CreatePositionRequest request) {
    var line = lineRepository.findById(lineId).orElseThrow(() -> new LineNotFoundException(lineId));
    if (!line.isActive()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "LINE_INACTIVE");
    }
    Position position = new Position();
    position.setIdLine(lineId);
    position.setIdZone(line.getIdZone());
    position.setPositionName(request.positionName());
    position.setMaximumCapacity(request.maximumCapacity());
    position.setSizeStockToSave(request.sizeStockToSave());
    position.setActive(false);
    position.setCurrentStock(0);
    position.setCreatedAt(Instant.now());
    return positionRepository.save(position);
  }

  public Position updatePosition(String id, UpdatePositionRequest request) {
    Position position =
        positionRepository.findById(id).orElseThrow(() -> new PositionNotFoundException(id));

    // Unassign takes priority
    if (Boolean.TRUE.equals(request.unassignProduct())) {
      position.setProductId(null);
      position.setCurrentStock(0);
    } else {
      // Guard: cannot assign a different product without unassigning first
      if (request.productId() != null
          && position.getProductId() != null
          && !position.getProductId().equals(request.productId())) {
        throw new PositionAlreadyOccupiedException(id);
      }
      if (request.productId() != null) {
        if (!productRepository.existsById(request.productId())) {
          throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND");
        }
        position.setProductId(request.productId());
      }

      // Guard: stock cannot exceed capacity or go negative
      int newStock =
          request.currentStock() != null ? request.currentStock() : position.getCurrentStock();
      if (newStock > position.getMaximumCapacity() || newStock < 0) {
        throw new StockExceedsCapacityException(newStock, position.getMaximumCapacity());
      }

      // Guard: stock cannot exceed container volume capacity
      String finalProductId =
          request.productId() != null ? request.productId() : position.getProductId();
      StockSize finalSize =
          request.sizeStockToSave() != null
              ? request.sizeStockToSave()
              : position.getSizeStockToSave();
      if (finalProductId != null && newStock > 0 && finalSize != null) {
        var product =
            productRepository
                .findById(finalProductId)
                .orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
        double totalVolume = product.getVolume() * newStock;
        if (totalVolume > finalSize.getVolumeCm3()) {
          int maxAllowed =
              product.getVolume() > 0 ? (int) (finalSize.getVolumeCm3() / product.getVolume()) : 0;
          throw new StockExceedsCapacityException(newStock, maxAllowed);
        }
      }

      if (request.currentStock() != null) position.setCurrentStock(newStock);
    }

    if (request.positionName() != null) position.setPositionName(request.positionName());
    if (request.isActive() != null) position.setActive(request.isActive());
    if (request.sizeStockToSave() != null) position.setSizeStockToSave(request.sizeStockToSave());

    return positionRepository.save(position);
  }

  public void deletePosition(String id) {
    Position position =
        positionRepository.findById(id).orElseThrow(() -> new PositionNotFoundException(id));
    position.setActive(false);
    positionRepository.save(position);
  }
}
