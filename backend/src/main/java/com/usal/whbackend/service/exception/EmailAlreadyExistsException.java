package com.usal.whbackend.service.exception;

public class EmailAlreadyExistsException extends RuntimeException {
  public EmailAlreadyExistsException(String email) {
    super("El email ya está en uso: " + email);
  }
}
