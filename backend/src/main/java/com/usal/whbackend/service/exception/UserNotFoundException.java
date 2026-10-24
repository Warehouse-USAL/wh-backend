package com.usal.whbackend.service.exception;

public class UserNotFoundException extends RuntimeException {
  public UserNotFoundException(String id) {
    super("Usuario no encontrado: " + id);
  }
}
