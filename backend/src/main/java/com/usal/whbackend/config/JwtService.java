package com.usal.whbackend.config;

import com.usal.whbackend.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class JwtService {

  private final SecretKey key;
  private final long expirationMs;

  /** Null when none is wired (plain unit tests): then only the signature and expiry count. */
  private UserActivityCache userActivity;

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

  @Autowired(required = false)
  public void setUserActivity(UserActivityCache userActivity) {
    this.userActivity = userActivity;
  }

  /**
   * Whether the token is signed with our key, unexpired, and still usable by its owner: the user it
   * names must exist and be active (see {@link UserActivityCache}), so deactivating an account
   * invalidates the tokens it already holds. Every authentication path (HTTP filter, WebSocket
   * handshakes, the console) goes through here.
   */
  public boolean isTokenValid(String token) {
    try {
      Claims claims = parseClaims(token);
      return userActivity == null || userActivity.isActive(claims.getSubject());
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

  /**
   * Extracts the expiry of a JWT token.
   *
   * @param token the JWT token
   * @return when the token stops being valid
   * @throws JwtException if the token is invalid, expired, or malformed
   */
  public Instant extractExpiration(String token) {
    return parseClaims(token).getExpiration().toInstant();
  }

  private Claims parseClaims(String token) {
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }
}
