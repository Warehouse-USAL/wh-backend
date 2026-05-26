package com.usal.whbackend.service.exception;

public class LineNotFoundException extends RuntimeException {
  public LineNotFoundException(String id) {
    super("Line not found: " + id);
  }
}
