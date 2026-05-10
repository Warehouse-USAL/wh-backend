package com.usal.whbackend.service;

import com.usal.whbackend.api.auth.LoginRequest;
import com.usal.whbackend.api.auth.LoginResponse;
import com.usal.whbackend.repository.UserRepository;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserRepository userRepository;

    public AuthService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public LoginResponse login(LoginRequest request) {
        throw new UnsupportedOperationException("not implemented");
    }
}
