package com.usal.whbackend.service.console.stream;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reference-counted sources: the first subscriber of a key creates (and starts or schedules) the
 * source, the last one to leave stops it at once. Polled sources are ticked by {@code scheduler}
 * (which only dispatches) and evaluated on {@code evaluators}, never on a request thread, with at
 * most one evaluation in flight per source: a slow store skips ticks rather than queueing them.
 */
final class SourceRegistry {

  private static final Logger log = LoggerFactory.getLogger(SourceRegistry.class);

  private record Entry(Source source, ScheduledFuture<?> schedule) {}

  private final Function<SourceKey, Source> factory;
  private final Function<SourceKey, Duration> intervals;
  private final ScheduledExecutorService scheduler;
  private final Executor evaluators;
  private final Map<SourceKey, Entry> sources = new HashMap<>();

  /**
   * @param intervals evaluation interval per key, or null for an event-driven source
   */
  SourceRegistry(
      Function<SourceKey, Source> factory,
      Function<SourceKey, Duration> intervals,
      ScheduledExecutorService scheduler,
      Executor evaluators) {
    this.factory = factory;
    this.intervals = intervals;
    this.scheduler = scheduler;
    this.evaluators = evaluators;
  }

  synchronized void acquire(SourceKey key, Subscriber subscriber) {
    Entry entry = sources.get(key);
    if (entry == null) {
      Source source = factory.apply(key);
      Duration interval = intervals.apply(key);
      ScheduledFuture<?> schedule = null;
      if (interval == null) {
        source.start();
      } else {
        schedule =
            scheduler.scheduleWithFixedDelay(
                () -> tick(source), 0, interval.toMillis(), TimeUnit.MILLISECONDS);
      }
      entry = new Entry(source, schedule);
      sources.put(key, entry);
      log.debug("Stream source started: {} ({} active)", kind(key), sources.size());
    }
    entry.source().join(subscriber);
  }

  synchronized void release(SourceKey key, Subscriber subscriber) {
    Entry entry = sources.get(key);
    if (entry == null || !entry.source().leave(subscriber)) {
      return;
    }
    sources.remove(key);
    if (entry.schedule() != null) {
      entry.schedule().cancel(false);
    }
    entry.source().stop();
    log.debug("Stream source stopped: {} ({} active)", kind(key), sources.size());
  }

  synchronized int size() {
    return sources.size();
  }

  private void tick(Source source) {
    if (!source.inFlight.compareAndSet(false, true)) {
      return;
    }
    try {
      evaluators.execute(
          () -> {
            try {
              source.evaluate();
            } catch (RuntimeException e) {
              log.debug("Stream source evaluation failed: {}", e.getClass().getSimpleName());
            } finally {
              source.inFlight.set(false);
            }
          });
    } catch (RejectedExecutionException e) {
      source.inFlight.set(false);
    }
  }

  /** Never the key itself: its filters may hold free text. */
  private static String kind(SourceKey key) {
    return key.getClass().getSimpleName();
  }
}
