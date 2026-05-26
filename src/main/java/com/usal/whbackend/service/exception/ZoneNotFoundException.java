package com.usal.whbackend.service.exception;

public class ZoneNotFoundException extends RuntimeException {
  public ZoneNotFoundException(String id) {
    super("Zone not found: " + id);
  }
}
