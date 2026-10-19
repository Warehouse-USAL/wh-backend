package com.usal.whbackend.repository;

import com.usal.whbackend.domain.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface IdempotencyRecordRepository extends MongoRepository<IdempotencyRecord, String> {

  Optional<IdempotencyRecord> findByKey(String key);
}
