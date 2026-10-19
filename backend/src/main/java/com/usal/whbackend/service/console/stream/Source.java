package com.usal.whbackend.service.console.stream;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;

/**
 * A shared source of frames: one per distinct {@link SourceKey}, fanned out to every subscriber.
 *
 * <p>Polled sources implement {@link #evaluate()}, which the registry runs on its interval with at
 * most one evaluation in flight. Event-driven sources implement {@link #start()} and {@link
 * #stop()} instead. Failures are reported once per distinct code: a 4xx is permanent (the source
 * stops asking), anything else is retried on the next evaluation.
 */
abstract class Source {

  static final String UNAVAILABLE = "SOURCE_UNAVAILABLE";

  private static final Logger log = LoggerFactory.getLogger(Source.class);

  protected final Object lock = new Object();
  protected final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();
  final AtomicBoolean inFlight = new AtomicBoolean();

  // Guarded by lock.
  private String errorCode;
  private boolean permanent;

  void join(Subscriber subscriber) {
    synchronized (lock) {
      subscribers.add(subscriber);
      if (!onJoin(subscriber) && errorCode != null) {
        subscriber.error(errorCode, permanent);
      }
    }
  }

  /** True when nobody is left. */
  boolean leave(Subscriber subscriber) {
    synchronized (lock) {
      subscribers.remove(subscriber);
      return subscribers.isEmpty();
    }
  }

  void start() {}

  void stop() {}

  void evaluate() {}

  /** Caller holds the lock. Sends what a newcomer should see; false when there is no data yet. */
  protected boolean onJoin(Subscriber subscriber) {
    return false;
  }

  /** Whether a permanent failure means there is no point in evaluating again. */
  protected final boolean failedPermanently() {
    synchronized (lock) {
      return permanent;
    }
  }

  /** Caller holds the lock: the next failure is reported even with the same code. */
  protected final void recovered() {
    errorCode = null;
  }

  protected final void failed(RuntimeException e) {
    String code;
    boolean isPermanent;
    if (e instanceof ResponseStatusException rse) {
      code =
          rse.getReason() != null ? rse.getReason() : String.valueOf(rse.getStatusCode().value());
      isPermanent = rse.getStatusCode().is4xxClientError();
    } else {
      log.debug("Stream source failed: {}", e.getClass().getSimpleName());
      code = UNAVAILABLE;
      isPermanent = false;
    }
    synchronized (lock) {
      permanent = isPermanent;
      if (code.equals(errorCode)) {
        return;
      }
      errorCode = code;
      onFailure();
      for (Subscriber s : subscribers) {
        s.error(code, isPermanent);
      }
    }
  }

  /** Caller holds the lock. Forgets data a failure made stale. */
  protected void onFailure() {}
}
