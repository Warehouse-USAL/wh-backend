package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Reception;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ReceptionRepository extends MongoRepository<Reception, String> {}
