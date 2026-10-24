package com.usal.whbackend.service;

import com.usal.whbackend.api.auth.LoginRequest;
import com.usal.whbackend.api.auth.LoginResponse;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.service.exception.AccountDisabledException;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;

  public AuthService(
      UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
  }

  public LoginResponse login(LoginRequest request) {
    User user =
        userRepository.findByEmail(request.email()).orElseThrow(InvalidCredentialsException::new);

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      throw new InvalidCredentialsException();
    }

    if (!user.isActive()) {
      throw new AccountDisabledException();
    }

    String token = jwtService.generateToken(user);
    return new LoginResponse(
        token,
        new LoginResponse.UserInfo(
            user.getId(), user.getName(), user.getEmail(), user.getRole().name()));
  }
}
