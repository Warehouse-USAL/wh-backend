package com.usal.whbackend.api.user;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record ResetPasswordRequest(@NotBlank @JsonProperty("new_password") String newPassword) {}
