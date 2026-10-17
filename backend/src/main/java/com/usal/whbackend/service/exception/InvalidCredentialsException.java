package com.usal.whbackend.service.exception;

public class InvalidCredentialsException extends RuntimeException {
  public InvalidCredentialsException() {
    super("Credenciales inválidas.");
  }
}
