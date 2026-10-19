package com.usal.whbackend.api.user;

public record UpdateUserRequest(String name, String role, Boolean active, String supplier) {

  /** Leaves the supplier unchanged. */
  public UpdateUserRequest(String name, String role, Boolean active) {
    this(name, role, active, null);
  }
}
