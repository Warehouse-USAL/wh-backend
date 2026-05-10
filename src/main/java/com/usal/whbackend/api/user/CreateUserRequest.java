package com.usal.whbackend.api.user;

public record CreateUserRequest(String email, String name, String role, String initialPassword) {}
