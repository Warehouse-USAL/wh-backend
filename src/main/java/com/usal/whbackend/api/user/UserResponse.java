package com.usal.whbackend.api.user;

import com.usal.whbackend.domain.User;
import java.time.Instant;

public record UserResponse(
    String id,
    String email,
    String name,
    String role,
    boolean active,
    Instant createdAt,
    AddressResponse address) {

  public record AddressResponse(
      String street, String department, String floor, String postalCode) {}

  public static UserResponse from(User user) {
    AddressResponse address = null;
    if (user.getAddress() != null) {
      address =
          new AddressResponse(
              user.getAddress().getStreet(),
              user.getAddress().getDepartment(),
              user.getAddress().getFloor(),
              user.getAddress().getPostalCode());
    }
    return new UserResponse(
        user.getId(),
        user.getEmail(),
        user.getName(),
        user.getRole().name(),
        user.isActive(),
        user.getCreatedAt(),
        address);
  }
}