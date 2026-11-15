package com.usal.whbackend.domain;

/**
 * What a {@link Reception} is. A {@code SUPPLIER} remito is a delivery from outside; a {@code
 * RELOCATION} is stock that was taken out of a position and still has to be put somewhere else — it
 * is already in the warehouse, so it is not a purchase.
 */
public enum ReceptionType {
  SUPPLIER,
  RELOCATION
}
