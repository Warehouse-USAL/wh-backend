package com.usal.whbackend.domain;

/**
 * Lifecycle of a {@link RestockOrder}. The restock RFC marked a status field as fuera de alcance
 * del milestone inicial — this enum adds it only for the cancellation flow (#137) and keeps the
 * default {@link #PENDING} so existing documents without the field read as pending.
 */
public enum RestockOrderStatus {
  PENDING,
  CANCELLED
}
