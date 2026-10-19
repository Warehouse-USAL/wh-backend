package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;

public interface OrderEventPublisher {
  void broadcastOrderUpdate(Order order);
}
