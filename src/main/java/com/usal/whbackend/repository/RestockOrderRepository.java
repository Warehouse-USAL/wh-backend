package com.usal.whbackend.repository;

import com.usal.whbackend.domain.RestockOrder;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RestockOrderRepository extends MongoRepository<RestockOrder, String> {}
