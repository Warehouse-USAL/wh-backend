package com.usal.whbackend.service;

import com.usal.whbackend.api.warehouse.position.CreatePositionRequest;
import com.usal.whbackend.api.warehouse.position.PositionSummaryResponse;
import com.usal.whbackend.api.warehouse.position.UpdatePositionRequest;
import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PositionService {

  private final PositionRepository positionRepository;
  private final LineRepository lineRepository;
  private final ZoneRepository zoneRepository;
  private final ProductRepository productRepository;
  private final MongoTemplate mongoTemplate;

  public PositionService(
      PositionRepository positionRepository,
      LineRepository lineRepository,
      ZoneRepository zoneRepository,
      ProductRepository productRepository,
      MongoTemplate mongoTemplate) {
    this.positionRepository = positionRepository;
    this.lineRepository = lineRepository;
    this.zoneRepository = zoneRepository;
    this.productRepository = productRepository;
    this.mongoTemplate = mongoTemplate;
  }

  /** Maximum whole units of {@code productVolume} that fit within {@code containerVolumeCm3}. */
  public static int maxUnitsByVolume(double productVolume, double containerVolumeCm3) {
    return productVolume > 0 ? (int) (containerVolumeCm3 / productVolume) : 0;
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
          throw new StockExceedsCapacityException(
              newStock, maxUnitsByVolume(product.getVolume(), finalSize.getVolumeCm3()));
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

  // ── Restock reception support ───────────────────────────────────────────────

  /**
   * Adds {@code quantity} units of {@code productId} to a position, enforcing the same guards as
   * {@link #updatePosition}: the position must be active, single-product, within numeric capacity
   * and within its volume budget. Used by reception registration to apply one assignment line —
   * callers loop this per assignment, so a failure partway through leaves earlier assignments
   * already applied (bounded by the caller's transaction, same as {@code StockDrainService.drain}).
   */
  public Position increaseStock(String positionId, String productId, int quantity) {
    Position current =
        positionRepository
            .findById(positionId)
            .orElseThrow(() -> new PositionNotFoundException(positionId));
    if (!current.isActive()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "POSITION_INACTIVE");
    }
    if (current.getProductId() != null && !current.getProductId().equals(productId)) {
      throw new PositionAlreadyOccupiedException(positionId);
    }

    int effectiveCap = current.getMaximumCapacity();
    StockSize size = current.getSizeStockToSave();
    if (size != null) {
      Product product =
          productRepository
              .findById(productId)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));
      if (product.getVolume() > 0) {
        effectiveCap =
            Math.min(effectiveCap, maxUnitsByVolume(product.getVolume(), size.getVolumeCm3()));
      }
    }

    // Atomic conditional increment: the capacity check is re-evaluated as part of the same
    // findAndModify that applies it, so a concurrent increaseStock on the same position can never
    // both pass the check and push stock past effectiveCap (unlike a read-check-then-save).
    Query query =
        Query.query(
            Criteria.where("_id").is(positionId).and("currentStock").lte(effectiveCap - quantity));
    Update update = new Update().inc("currentStock", quantity).set("productId", productId);
    Position updated =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), Position.class);
    if (updated == null) {
      Position latest =
          positionRepository
              .findById(positionId)
              .orElseThrow(() -> new PositionNotFoundException(positionId));
      throw new StockExceedsCapacityException(latest.getCurrentStock() + quantity, effectiveCap);
    }
    return updated;
  }

  /**
   * Candidate positions for placing an incoming reception: active, sized for {@code deliveryUnit},
   * and either empty or already holding {@code productId} (a position holds a single product).
   * Ordered by descending available capacity so an operator fills the roomiest position first.
   */
  public List<AvailablePosition> getAvailablePositions(
      String productId, StockSize deliveryUnit, int quantity) {
    if (quantity <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY");
    }
    Product product =
        productRepository
            .findById(productId)
            .filter(Product::isActive)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND"));

    return positionRepository.findByIsActiveTrueAndSizeStockToSave(deliveryUnit).stream()
        .filter(p -> p.getProductId() == null || p.getProductId().equals(productId))
        .map(p -> toAvailablePosition(p, product, deliveryUnit))
        .filter(ap -> ap.availableUnits() > 0)
        .sorted(Comparator.comparingInt(AvailablePosition::availableUnits).reversed())
        .toList();
  }

  private AvailablePosition toAvailablePosition(
      Position p, Product product, StockSize deliveryUnit) {
    int byCount = p.getMaximumCapacity() - p.getCurrentStock();
    int byVolume =
        product.getVolume() > 0
            ? maxUnitsByVolume(product.getVolume(), deliveryUnit.getVolumeCm3())
                - p.getCurrentStock()
            : byCount;
    int available = Math.max(0, Math.min(byCount, byVolume));
    return new AvailablePosition(p.getId(), p.getPositionName(), available);
  }

  public record AvailablePosition(String positionId, String positionName, int availableUnits) {}
}
