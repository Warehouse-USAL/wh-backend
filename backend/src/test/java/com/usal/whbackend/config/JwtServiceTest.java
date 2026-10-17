package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

  private JwtService jwtService;
  private User user;

  @BeforeEach
  void setUp() {
    jwtService = new JwtService("test-secret-key-for-unit-tests-min32ch", 86400000L);
    user = new User();
    user.setId("USR-001");
    user.setEmail("admin@test.com");
    user.setRole(UserRole.ADMIN_SYSTEM);
  }

  @Test
  void generateToken_returnsNonEmptyString() {
    assertThat(jwtService.generateToken(user)).isNotEmpty();
  }

  @Test
  void isTokenValid_returnsTrueForValidToken() {
    assertThat(jwtService.isTokenValid(jwtService.generateToken(user))).isTrue();
  }

  @Test
  void isTokenValid_returnsFalseForTamperedToken() {
    assertThat(jwtService.isTokenValid(jwtService.generateToken(user) + "x")).isFalse();
  }

  @Test
  void isTokenValid_returnsFalseForExpiredToken() {
    JwtService expired = new JwtService("test-secret-key-for-unit-tests-min32ch", -1000L);
    assertThat(jwtService.isTokenValid(expired.generateToken(user))).isFalse();
  }

  @Test
  void extractUserId_returnsCorrectValue() {
    assertThat(jwtService.extractUserId(jwtService.generateToken(user))).isEqualTo("USR-001");
  }

  @Test
  void extractEmail_returnsCorrectValue() {
    assertThat(jwtService.extractEmail(jwtService.generateToken(user))).isEqualTo("admin@test.com");
  }

  @Test
  void extractRole_returnsCorrectValue() {
    assertThat(jwtService.extractRole(jwtService.generateToken(user))).isEqualTo("ADMIN_SYSTEM");
  }

  @Test
  void isTokenValid_returnsFalseForTokenSignedWithDifferentKey() {
    JwtService otherService = new JwtService("different-secret-key-also-min32chars!!", 86400000L);
    String tokenFromOtherService = otherService.generateToken(user);
    assertThat(jwtService.isTokenValid(tokenFromOtherService)).isFalse();
  }
}
