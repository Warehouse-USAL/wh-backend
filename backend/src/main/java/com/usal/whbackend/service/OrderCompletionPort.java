package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import java.time.Instant;

/** Port for finishing an order, called by the Kafka order-status consumer. */
public interface OrderCompletionPort {
  void completeOrder(Order order, Instant completedAt);
}
