package com.usal.whbackend.service;

import com.usal.whbackend.domain.Product;

public interface StockEventPublisher {
  void broadcastStockAlert(Product product, int currentStock);
}
