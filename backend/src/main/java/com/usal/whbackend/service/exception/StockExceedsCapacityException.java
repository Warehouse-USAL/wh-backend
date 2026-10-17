package com.usal.whbackend.service.exception;

public class StockExceedsCapacityException extends RuntimeException {
  public StockExceedsCapacityException(int stock, int capacity) {
    super("Stock " + stock + " exceeds capacity " + capacity);
  }
}
