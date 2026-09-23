package com.usal.whbackend.service.exception;

/** A computed metric's params are missing, out of range or inconsistent. Maps to 400. */
public class InvalidMetricParamsException extends RuntimeException {
  public InvalidMetricParamsException(String message) {
    super(message);
  }
}
