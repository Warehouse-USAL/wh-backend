package com.usal.whbackend.service;

import com.usal.whbackend.api.auth.LoginRequest;
import com.usal.whbackend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock UserRepository userRepository;
    @InjectMocks AuthService authService;

    @Test
    void login_throwsUnsupported() {
        assertThrows(UnsupportedOperationException.class,
                () -> authService.login(new LoginRequest("user@example.com", "pass")));
    }
}
