package com.usal.whbackend.service;

import com.usal.whbackend.domain.OrderItem;
import java.util.List;

/** Port for FIFO position-stock drain, called by the Kafka order-status consumer. */
public interface StockDrainPort {
  void drain(List<OrderItem> items);
}
