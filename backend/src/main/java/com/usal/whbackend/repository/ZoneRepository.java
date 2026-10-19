package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Zone;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ZoneRepository extends MongoRepository<Zone, String> {
  Optional<Zone> findByZoneCode(String zoneCode);
}
