package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.repository.LineRepository;
import com.usal.whbackend.repository.PositionRepository;
import com.usal.whbackend.repository.ProductRepository;
import com.usal.whbackend.service.exception.LineNotFoundException;
import com.usal.whbackend.service.exception.PositionAlreadyOccupiedException;
import com.usal.whbackend.service.exception.PositionNotFoundException;
import com.usal.whbackend.service.exception.StockExceedsCapacityException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PositionService {

  private final PositionRepository positionRepository;
  private final LineRepository lineRepository;
  private final ProductRepository productRepository;

  public PositionService(
      PositionRepository positionRepository,
      LineRepository lineRepository,
      ProductRepository productRepository) {
    this.positionRepository = positionRepository;
    this.lineRepository = lineRepository;
    this.productRepository = productRepository;
  }

  public List<Position> getPositionsByLine(String lineId) {
    lineRepository.findById(lineId).orElseThrow(() -> new LineNotFoundException(lineId));
    return positionRepository.findByIdLine(lineId);
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
