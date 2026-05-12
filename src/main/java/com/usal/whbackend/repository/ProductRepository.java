package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ProductRepository extends MongoRepository<Product, String> {

    Optional<Product> findBySku(String sku);

    List<Product> findByCategory(String category);

    List<Product> findByActive(boolean active);

    List<Product> findByCategoryAndActive(String category, boolean active);
}
