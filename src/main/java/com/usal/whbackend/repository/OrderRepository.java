package com.usal.whbackend.repository;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderStatus;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderRepository extends MongoRepository<Order, String> {

    List<Order> findByStatus(OrderStatus status);

    List<Order> findByRequestedByUserId(String userId);

    List<Order> findByAssignedVehicleId(String vehicleId);
}
