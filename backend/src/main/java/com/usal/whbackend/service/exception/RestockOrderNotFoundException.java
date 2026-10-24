package com.usal.whbackend.service.exception;

public class RestockOrderNotFoundException extends RuntimeException {
  public RestockOrderNotFoundException(String id) {
    super("Restock order not found: " + id);
  }
}
