package com.usal.whbackend.service.exception;

public class AccountDisabledException extends RuntimeException {
  public AccountDisabledException() {
    super("La cuenta está deshabilitada.");
  }
}
