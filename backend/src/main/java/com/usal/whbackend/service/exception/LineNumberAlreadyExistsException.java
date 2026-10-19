package com.usal.whbackend.service.exception;

public class LineNumberAlreadyExistsException extends RuntimeException {
  public LineNumberAlreadyExistsException(int number, String zoneId) {
    super("Line number " + number + " already exists in zone " + zoneId);
  }
}
