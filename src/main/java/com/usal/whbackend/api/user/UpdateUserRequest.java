package com.usal.whbackend.api.user;

public record UpdateUserRequest(String name, String role, Boolean active) {}
