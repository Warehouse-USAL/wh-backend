package com.usal.whbackend.service;

import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.Position;
import java.util.List;

/** Port for the FIFO position-stock drain: applied on completion, previewed when dispatching. */
public interface StockDrainPort {
  void drain(List<OrderItem> items);

  /** Units {@link #drain} would take from one position for one order item. */
  record Pick(Position position, int quantity) {}

  /**
   * What {@link #drain} would take from each position for these items, without touching anything.
   * Same FIFO selection as the drain, so a vehicle is sent where the stock will actually be
   * consumed, and for as many units as it has to pick there.
   */
  List<Pick> picksToConsume(List<OrderItem> items);

  /**
   * The positions {@link #drain} would take stock from for these items, one entry per item and
   * position, in the order {@link #picksToConsume} gives them.
   */
  default List<Position> positionsToConsume(List<OrderItem> items) {
    return picksToConsume(items).stream().map(Pick::position).toList();
  }
}
