package com.usal.whbackend.service.exception;

public class ReceptionNotFoundException extends RuntimeException {
  public ReceptionNotFoundException(String id) {
    super("Reception not found: " + id);
  }
}
