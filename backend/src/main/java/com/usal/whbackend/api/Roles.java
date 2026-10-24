package com.usal.whbackend.api;

import com.usal.whbackend.domain.UserRole;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * Bridges Spring Security's {@code ROLE_X} authorities and the domain's {@link UserRole}.
 *
 * <p>The catalogue registries decide visibility per role, so they need the domain enum rather than
 * authority strings. Authorities that do not map to a known role are dropped rather than raising —
 * an unrecognised authority should narrow what a caller can see, never fail their request.
 */
public final class Roles {

  private static final String PREFIX = "ROLE_";

  private Roles() {}

  public static Set<UserRole> of(Authentication authentication) {
    if (authentication == null) {
      return Set.of();
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .map(a -> a.startsWith(PREFIX) ? a.substring(PREFIX.length()) : a)
        .map(a -> a.toUpperCase(Locale.ROOT))
        .map(Roles::parse)
        .filter(r -> r != null)
        .collect(Collectors.toSet());
  }

  private static UserRole parse(String name) {
    try {
      return UserRole.valueOf(name);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
