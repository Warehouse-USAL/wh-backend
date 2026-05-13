package com.usal.whbackend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.auth.LoginRequest;
import com.usal.whbackend.api.auth.LoginResponse;
import com.usal.whbackend.config.JwtService;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.repository.UserRepository;
import com.usal.whbackend.service.exception.AccountDisabledException;
import com.usal.whbackend.service.exception.InvalidCredentialsException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;
  @Mock JwtService jwtService;
  @InjectMocks AuthService authService;

  private User activeUser() {
    User u = new User();
    u.setId("USR-001");
    u.setEmail("admin@test.com");
    u.setName("Admin");
    u.setRole(UserRole.ADMIN_SYSTEM);
    u.setActive(true);
    u.setPasswordHash("$2a$10$hash");
    return u;
  }

  @Test
  void login_validCredentials_returnsTokenAndUserInfo() {
    User user = activeUser();
    when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("password", "$2a$10$hash")).thenReturn(true);
    when(jwtService.generateToken(user)).thenReturn("jwt.token");

    LoginResponse r = authService.login(new LoginRequest("admin@test.com", "password"));

    assertThat(r.token()).isEqualTo("jwt.token");
    assertThat(r.user().id()).isEqualTo("USR-001");
    assertThat(r.user().role()).isEqualTo("ADMIN_SYSTEM");
  }

  @Test
  void login_unknownEmail_throwsInvalidCredentials() {
    when(userRepository.findByEmail("x@x.com")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> authService.login(new LoginRequest("x@x.com", "pass")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void login_wrongPassword_throwsInvalidCredentials() {
    User user = activeUser();
    when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "$2a$10$hash")).thenReturn(false);
    assertThatThrownBy(() -> authService.login(new LoginRequest("admin@test.com", "wrong")))
        .isInstanceOf(InvalidCredentialsException.class);
  }

  @Test
  void login_disabledAccount_throwsAccountDisabled() {
    User user = activeUser();
    user.setActive(false);
    when(userRepository.findByEmail("admin@test.com")).thenReturn(Optional.of(user));
    assertThatThrownBy(() -> authService.login(new LoginRequest("admin@test.com", "password")))
        .isInstanceOf(AccountDisabledException.class);
  }
}
