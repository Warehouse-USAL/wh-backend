package com.usal.whbackend.service.exception;

public class PositionAlreadyOccupiedException extends RuntimeException {
  public PositionAlreadyOccupiedException(String positionId) {
    super("Position already occupied: " + positionId);
  }
}
