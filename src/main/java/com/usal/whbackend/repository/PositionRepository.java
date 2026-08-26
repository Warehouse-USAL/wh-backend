package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Position;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PositionRepository extends MongoRepository<Position, String> {
  List<Position> findByIdLine(String idLine);

  List<Position> findByProductIdAndCurrentStockGreaterThanOrderByCreatedAtAsc(
      String productId, int minStock);

  /** FIFO drain — only drain active positions. */
  List<Position> findByProductIdAndIsActiveTrueAndCurrentStockGreaterThanOrderByCreatedAtAsc(
      String productId, int minStock);

  /**
   * Flat dashboard listing — occupied positions only: an assigned product with stock on hand, in a
   * position that is still active. Inactive positions are excluded for the same reason the stock
   * queries above exclude them: {@code deletePosition} soft-deletes by flipping {@code isActive}
   * without clearing {@code productId}/{@code currentStock}, so a deleted position still looks
   * occupied on the document.
   */
  List<Position> findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan(int minStock);

  List<Position> findByProductIdIn(List<String> productIds);

  /** Stock computation — only count active positions. */
  List<Position> findByProductIdInAndIsActiveTrue(List<String> productIds);
}
