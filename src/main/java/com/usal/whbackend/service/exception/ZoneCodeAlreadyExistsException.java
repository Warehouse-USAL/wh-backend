package com.usal.whbackend.service.exception;

public class ZoneCodeAlreadyExistsException extends RuntimeException {
  public ZoneCodeAlreadyExistsException(String code) {
    super("Zone code already exists: " + code);
  }
}
