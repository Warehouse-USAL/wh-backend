package com.usal.whbackend.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class UserTest {

    @Test
    void gettersAndSetters() {
        User user = new User();
        Instant now = Instant.now();

        user.setId("id-1");
        user.setEmail("test@example.com");
        user.setName("Test User");
        user.setRole(UserRole.OPERATOR);
        user.setActive(true);
        user.setPasswordHash("hashed");
        user.setCreatedAt(now);

        assertEquals("id-1", user.getId());
        assertEquals("test@example.com", user.getEmail());
        assertEquals("Test User", user.getName());
        assertEquals(UserRole.OPERATOR, user.getRole());
        assertTrue(user.isActive());
        assertEquals("hashed", user.getPasswordHash());
        assertEquals(now, user.getCreatedAt());
    }
}
