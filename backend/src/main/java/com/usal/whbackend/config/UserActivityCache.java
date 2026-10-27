package com.usal.whbackend.config;

import com.usal.whbackend.repository.UserRepository;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether a user may still use the JWTs it holds: it exists and is active. A token is stateless and
 * stays signed-valid until it expires, so a deactivated or deleted user would otherwise keep
 * working. The answer is cached for a short TTL so authenticating does not cost a database read per
 * request; {@link #evict} drops it at once when this instance deactivates the user, while other
 * instances (and the cache itself) converge within the TTL.
 *
 * <p>If the user store cannot be read the last known answer is used, and with none the token is
 * accepted: the signature is valid, and a request that needs the database fails there (503) anyway.
 */
@Component
public class UserActivityCache {

  private static final Logger log = LoggerFactory.getLogger(UserActivityCache.class);

  /** Upper bound on remembered users; the map is cleared when it is reached. */
  private static final int MAX_ENTRIES = 10_000;

  private record Entry(boolean active, long expiresAtNanos) {}

  private final UserRepository userRepository;
  private final long ttlNanos;
  private final LongSupplier nanoClock;
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  @Autowired
  public UserActivityCache(
      UserRepository userRepository, @Value("${jwt.user-status-cache-ttl-ms:15000}") long ttlMs) {
    this(userRepository, ttlMs, System::nanoTime);
  }

  UserActivityCache(UserRepository userRepository, long ttlMs, LongSupplier nanoClock) {
    this.userRepository = userRepository;
    this.ttlNanos = Math.max(0, ttlMs) * 1_000_000L;
    this.nanoClock = nanoClock;
  }

  /** Whether {@code userId} exists and is active (cached for the TTL). */
  public boolean isActive(String userId) {
    if (userId == null || userId.isBlank()) {
      return false;
    }
    long now = nanoClock.getAsLong();
    Entry cached = entries.get(userId);
    if (cached != null && now - cached.expiresAtNanos < 0) {
      return cached.active;
    }
    try {
      boolean active = userRepository.findById(userId).map(u -> u.isActive()).orElse(false);
      if (ttlNanos > 0) {
        if (entries.size() >= MAX_ENTRIES) {
          entries.clear();
        }
        entries.put(userId, new Entry(active, now + ttlNanos));
      }
      return active;
    } catch (RuntimeException e) {
      log.warn("could not check whether user {} is active: {}", userId, e.toString());
      return cached == null || cached.active;
    }
  }

  /** Forgets what is known about {@code userId}, e.g. right after it was deactivated. */
  public void evict(String userId) {
    if (userId != null) {
      entries.remove(userId);
    }
  }
}
