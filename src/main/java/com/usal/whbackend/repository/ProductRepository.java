package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;

public interface ProductRepository extends MongoRepository<Product, String> {

    Optional<Product> findBySku(String sku);

    List<Product> findByCategory(String category);

    List<Product> findByActive(boolean active);

    List<Product> findByCategoryAndActive(String category, boolean active);

    // Atomic $inc to avoid race conditions on stock updates (RFC §7)
    @Query("{ '_id': ?0 }")
    @Update("{ '$inc': { 'availableStock': ?1, 'reservedStock': ?2 } }")
    void updateStock(String id, int availableDelta, int reservedDelta);
}
