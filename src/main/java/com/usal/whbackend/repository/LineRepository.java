package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Line;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface LineRepository extends MongoRepository<Line, String> {
  List<Line> findByIdZone(String idZone);

  Optional<Line> findByIdZoneAndNumberLine(String idZone, int numberLine);
}
