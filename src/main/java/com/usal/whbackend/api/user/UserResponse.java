package com.usal.whbackend.api.user;

import com.usal.whbackend.domain.Address;
import java.time.Instant;

public record UserResponse(
    String id,
    String email,
    String name,
    String role,
    boolean active,
    Instant createdAt,
    Address address) {}