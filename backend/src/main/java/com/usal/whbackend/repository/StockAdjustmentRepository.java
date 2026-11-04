package com.usal.whbackend.repository;

import com.usal.whbackend.domain.StockAdjustment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StockAdjustmentRepository extends MongoRepository<StockAdjustment, String> {

  Page<StockAdjustment> findByPositionIdOrderByCreatedAtDesc(String positionId, Pageable pageable);

  Page<StockAdjustment> findByProductIdOrderByCreatedAtDesc(String productId, Pageable pageable);
}
