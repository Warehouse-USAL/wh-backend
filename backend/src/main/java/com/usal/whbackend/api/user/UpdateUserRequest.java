package com.usal.whbackend.api.user;

public record UpdateUserRequest(String name, String role, Boolean active, String supplier) {

  /** Null leaves the supplier unchanged; a blank value clears it. */
  public UpdateUserRequest(String name, String role, Boolean active) {
    this(name, role, active, null);
  }
}
