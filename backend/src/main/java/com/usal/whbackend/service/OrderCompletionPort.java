package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import java.time.Instant;

/** Port for finishing an order, called by the Infra order-status service. */
public interface OrderCompletionPort {
  void completeOrder(Order order, Instant completedAt);
}
