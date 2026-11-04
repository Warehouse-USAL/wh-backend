package com.usal.whbackend.service;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.StockAdjustment;
import java.util.List;

/** A recorded adjustment and the orders it cancelled because stock no longer covered them. */
public record StockAdjustmentResult(StockAdjustment adjustment, List<Order> cancelledOrders) {

  public StockAdjustmentResult {
    cancelledOrders = List.copyOf(cancelledOrders);
  }
}
