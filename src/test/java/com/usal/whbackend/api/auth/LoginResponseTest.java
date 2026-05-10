package com.usal.whbackend.api.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginResponseTest {

    @Test
    void recordAccessors() {
        LoginResponse.UserInfo user = new LoginResponse.UserInfo("id-1", "Test", "test@example.com", "OPERATOR");
        LoginResponse response = new LoginResponse("jwt-token", user);

        assertEquals("jwt-token", response.token());
        assertEquals(user, response.user());
        assertEquals("id-1", user.id());
        assertEquals("Test", user.name());
        assertEquals("test@example.com", user.email());
        assertEquals("OPERATOR", user.role());
    }
}
