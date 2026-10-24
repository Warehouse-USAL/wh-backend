package com.usal.whbackend.service;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import java.util.List;

/** Port for the FIFO position-stock drain: applied on completion, previewed when dispatching. */
public interface StockDrainPort {
  void drain(List<OrderItem> items);

  /**
   * The positions {@link #drain} would take stock from for these items, without touching them. Same
   * FIFO selection as the drain, so a vehicle is sent where the stock will actually be consumed.
   */
  List<Position> positionsToConsume(List<OrderItem> items);
}
