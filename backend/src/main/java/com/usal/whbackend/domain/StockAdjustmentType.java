package com.usal.whbackend.domain;

/** Why a position's stock changed outside the order-fulfilment drain and restock receptions. */
public enum StockAdjustmentType {
  /** Stock raised by hand, e.g. a count correction. */
  MANUAL_INCREASE,
  /** Stock lowered by hand, e.g. a count correction. */
  MANUAL_DECREASE,
  /** Units lost to breakage or damage. */
  DAMAGE,
  /** The product was unassigned from the position, taking its stock out of the warehouse. */
  UNASSIGNED,
  /** The position was deactivated, so its stock no longer counts as available. */
  DEACTIVATED
}
