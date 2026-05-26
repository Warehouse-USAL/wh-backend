package com.usal.whbackend.service.exception;

public class PositionNotFoundException extends RuntimeException {
  public PositionNotFoundException(String id) {
    super("Position not found: " + id);
  }
}
