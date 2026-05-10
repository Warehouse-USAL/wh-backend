package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface VehicleRepository extends MongoRepository<Vehicle, String> {

    List<Vehicle> findByStatus(VehicleStatus status);
}
