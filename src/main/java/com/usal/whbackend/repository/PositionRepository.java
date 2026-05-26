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

  List<Position> findByProductIdIn(List<String> productIds);

  /** Stock computation — only count active positions. */
  List<Position> findByProductIdInAndIsActiveTrue(List<String> productIds);
}
