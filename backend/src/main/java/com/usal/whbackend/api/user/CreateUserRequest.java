package com.usal.whbackend.api.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotBlank String role,
    @NotBlank @JsonProperty("initial_password") String initialPassword,
    String supplier) {

  /** Without a supplier, for every role but PROVIDER. */
  public CreateUserRequest(String email, String name, String role, String initialPassword) {
    this(email, name, role, initialPassword, null);
  }
}
