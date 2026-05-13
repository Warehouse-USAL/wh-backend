package com.usal.whbackend.api.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record CreateUserRequest(
    @NotBlank @Email String email,
    @NotBlank String name,
    @NotBlank String role,
    @NotBlank String initialPassword) {}
