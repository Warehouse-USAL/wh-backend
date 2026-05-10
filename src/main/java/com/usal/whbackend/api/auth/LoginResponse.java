package com.usal.whbackend.api.auth;

public record LoginResponse(String token, UserInfo user) {
    public record UserInfo(String id, String name, String email, String role) {}
}
