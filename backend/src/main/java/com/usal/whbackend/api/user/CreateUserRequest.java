package com.usal.whbackend.api.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Supplier is optional; PROVIDER accounts need one to use supplier-scoped operations. */
public record CreateUserRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotBlank String role,
    @NotBlank @JsonProperty("initial_password") String initialPassword,
    String supplier) {

  /** Creates an unlinked account; supplier-scoped PROVIDER operations remain unavailable. */
  public CreateUserRequest(String email, String name, String role, String initialPassword) {
    this(email, name, role, initialPassword, null);
  }
}
