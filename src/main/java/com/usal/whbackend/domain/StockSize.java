package com.usal.whbackend.domain;

public enum StockSize {
  CAJA(48000.0),
  MEDIO_PALLET(900000.0),
  PALLET(1800000.0);

  private final double volumeCm3;

  StockSize(double volumeCm3) {
    this.volumeCm3 = volumeCm3;
  }

  public double getVolumeCm3() {
    return volumeCm3;
  }
}
