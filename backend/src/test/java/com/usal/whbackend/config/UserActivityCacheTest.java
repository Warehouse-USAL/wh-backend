package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.User;
import com.usal.whbackend.repository.UserRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class UserActivityCacheTest {

  private static final long TTL_MS = 10_000;

  private final UserRepository users = mock(UserRepository.class);
  private final AtomicLong nanos = new AtomicLong();
  private UserActivityCache cache;

  @BeforeEach
  void setUp() {
    cache = new UserActivityCache(users, TTL_MS, nanos::get);
  }

  private static User user(boolean active) {
    User u = new User();
    u.setId("u1");
    u.setActive(active);
    return u;
  }

  @Test
  void anActiveUserIsActive() {
    when(users.findById("u1")).thenReturn(Optional.of(user(true)));

    assertThat(cache.isActive("u1")).isTrue();
  }

  @Test
  void aDeactivatedUserIsNotActive() {
    when(users.findById("u1")).thenReturn(Optional.of(user(false)));

    assertThat(cache.isActive("u1")).isFalse();
  }

  @Test
  void aDeletedOrUnknownUserIsNotActive() {
    when(users.findById("u1")).thenReturn(Optional.empty());

    assertThat(cache.isActive("u1")).isFalse();
  }

  @Test
  void aMissingSubjectIsNotActiveAndNeverReachesTheStore() {
    assertThat(cache.isActive(null)).isFalse();
    assertThat(cache.isActive(" ")).isFalse();
    verifyNoInteractions(users);
  }

  @Test
  void theAnswerIsCachedUntilTheTtlPasses() {
    when(users.findById("u1")).thenReturn(Optional.of(user(true)));

    cache.isActive("u1");
    nanos.addAndGet(TTL_MS * 1_000_000L - 1);
    cache.isActive("u1");
    verify(users, times(1)).findById("u1");

    nanos.addAndGet(2);
    cache.isActive("u1");
    verify(users, times(2)).findById("u1");
  }

  @Test
  void theNewStateIsSeenOnceTheTtlPasses() {
    when(users.findById("u1")).thenReturn(Optional.of(user(true)), Optional.of(user(false)));

    assertThat(cache.isActive("u1")).isTrue();
    assertThat(cache.isActive("u1")).isTrue();
    nanos.addAndGet(TTL_MS * 1_000_000L + 1);
    assertThat(cache.isActive("u1")).isFalse();
  }

  @Test
  void evictMakesTheNextCheckReadTheStore() {
    when(users.findById("u1")).thenReturn(Optional.of(user(true)), Optional.of(user(false)));

    assertThat(cache.isActive("u1")).isTrue();
    cache.evict("u1");

    assertThat(cache.isActive("u1")).isFalse();
  }

  @Test
  void aZeroTtlChecksTheStoreEveryTime() {
    UserActivityCache uncached = new UserActivityCache(users, 0, nanos::get);
    when(users.findById("u1")).thenReturn(Optional.of(user(true)), Optional.of(user(false)));

    assertThat(uncached.isActive("u1")).isTrue();
    assertThat(uncached.isActive("u1")).isFalse();
  }

  @Test
  void whenTheStoreFailsTheLastKnownAnswerIsUsed() {
    when(users.findById("u1"))
        .thenReturn(Optional.of(user(false)))
        .thenThrow(new DataAccessResourceFailureException("mongo down"));

    assertThat(cache.isActive("u1")).isFalse();
    nanos.addAndGet(TTL_MS * 1_000_000L + 1);

    assertThat(cache.isActive("u1")).isFalse();
  }

  @Test
  void whenTheStoreFailsAndNothingIsKnownTheSignedTokenIsAccepted() {
    when(users.findById("u1")).thenThrow(new DataAccessResourceFailureException("mongo down"));

    assertThat(cache.isActive("u1")).isTrue();
  }

  @Test
  void aFailedLookupIsNotRetriedUntilTheTtlPasses() {
    when(users.findById("u1")).thenThrow(new DataAccessResourceFailureException("mongo down"));

    cache.isActive("u1");
    cache.isActive("u1");

    verify(users, times(1)).findById("u1");
  }
}
