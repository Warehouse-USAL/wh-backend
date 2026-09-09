package com.usal.whbackend.api.user;

public record UpdateMeRequest(String name, AddressRequest address) {
  public record AddressRequest(String street, String department, String floor, String postalCode) {}
}
