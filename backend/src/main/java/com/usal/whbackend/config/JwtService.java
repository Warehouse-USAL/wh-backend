package com.usal.whbackend.config;

import com.usal.whbackend.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class JwtService {

  private final SecretKey key;
  private final long expirationMs;

  public JwtService(
      @Value("${jwt.secret}") String secret, @Value("${jwt.expiration-ms}") long expirationMs) {
    this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.expirationMs = expirationMs;
  }

  public String generateToken(User user) {
    long now = System.currentTimeMillis();
    return Jwts.builder()
        .subject(user.getId())
        .claim("email", user.getEmail())
        .claim("role", user.getRole().name())
        .issuedAt(new Date(now))
        .expiration(new Date(now + expirationMs))
        .signWith(key)
        .compact();
  }

  public boolean isTokenValid(String token) {
    try {
      parseClaims(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Extracts the user ID from a JWT token.
   *
   * @param token the JWT token
   * @return the user ID claim from the token
   * @throws JwtException if the token is invalid, expired, or malformed
   */
  public String extractUserId(String token) {
    return parseClaims(token).getSubject();
  }

  /**
   * Extracts the email from a JWT token.
   *
   * @param token the JWT token
   * @return the email claim from the token
   * @throws JwtException if the token is invalid, expired, or malformed
   */
  public String extractEmail(String token) {
    return parseClaims(token).get("email", String.class);
  }

  /**
   * Extracts the role from a JWT token.
   *
   * @param token the JWT token
   * @return the role claim from the token
   * @throws JwtException if the token is invalid, expired, or malformed
   */
  public String extractRole(String token) {
    return parseClaims(token).get("role", String.class);
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
