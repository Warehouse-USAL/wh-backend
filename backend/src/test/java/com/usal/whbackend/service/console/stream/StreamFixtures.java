package com.usal.whbackend.service.console.stream;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Shared helpers for the stream tests. */
final class StreamFixtures {

  /** The app's wire format: snake_case, ISO instants. */
  static final JsonMapper JSON =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  private StreamFixtures() {}

  /** A subscriber whose frames land in {@code sink}, written on the calling thread. */
  static Subscriber subscriber(String id, RecordingSink sink) {
    return new Subscriber(new StreamSession(sink, 100, Runnable::run, s -> {}), id);
  }

  static List<String> frames(RecordingSink sink) {
    return sink.frames();
  }

  /** A clock tests can move. */
  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void set(Instant instant) {
      now = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
