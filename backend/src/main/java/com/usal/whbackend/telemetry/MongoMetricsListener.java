package com.usal.whbackend.telemetry;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import com.mongodb.event.ConnectionCheckedInEvent;
import com.mongodb.event.ConnectionCheckedOutEvent;
import com.mongodb.event.ConnectionClosedEvent;
import com.mongodb.event.ConnectionCreatedEvent;
import com.mongodb.event.ConnectionPoolListener;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bson.BsonValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Feeds the MongoDB driver's command and pool events into {@link TelemetryPort}.
 *
 * <p>Only data commands on a named collection are recorded; handshakes, pings and other admin
 * commands carry no collection and are ignored. The driver reports the command document only on the
 * started event, so the collection is remembered per request id until the command finishes. Never
 * throws: it runs on the driver's I/O threads.
 */
public class MongoMetricsListener implements CommandListener, ConnectionPoolListener {

  /** Connections the pool has handed out versus is holding idle. */
  public record PoolStats(int inUse, int available) {}

  private static final Logger log = LoggerFactory.getLogger(MongoMetricsListener.class);

  /** Bound on commands in flight, so a driver that never reports completion cannot leak memory. */
  private static final int MAX_PENDING = 10_000;

  private static final Set<String> RECORDED =
      Set.of("find", "insert", "update", "delete", "aggregate", "count", "findAndModify");

  private final TelemetryPort telemetry;
  private final long slowMs;
  private final Map<Integer, String> collectionByRequest = new ConcurrentHashMap<>();
  private final AtomicInteger created = new AtomicInteger();
  private final AtomicInteger closed = new AtomicInteger();
  private final AtomicInteger checkedOut = new AtomicInteger();
  private final AtomicInteger checkedIn = new AtomicInteger();

  public MongoMetricsListener(TelemetryPort telemetry, TelemetryProperties props) {
    this.telemetry = telemetry;
    this.slowMs = props.getMongoSlowMs();
  }

  @Override
  public void commandStarted(CommandStartedEvent event) {
    try {
      if (!RECORDED.contains(event.getCommandName())) {
        return;
      }
      BsonValue target = event.getCommand().get(event.getCommandName());
      if (target != null && target.isString() && collectionByRequest.size() < MAX_PENDING) {
        collectionByRequest.put(event.getRequestId(), target.asString().getValue());
      }
    } catch (RuntimeException e) {
      log.warn("Failed to observe mongo command start: {}", e.getMessage());
    }
  }

  @Override
  public void commandSucceeded(CommandSucceededEvent event) {
    try {
      double ms = event.getElapsedTime(TimeUnit.NANOSECONDS) / 1_000_000.0;
      finish(event.getRequestId(), event.getCommandName(), ms >= slowMs ? "slow" : "ok", ms);
    } catch (RuntimeException e) {
      log.warn("Failed to record mongo command: {}", e.getMessage());
    }
  }

  @Override
  public void commandFailed(CommandFailedEvent event) {
    try {
      finish(
          event.getRequestId(),
          event.getCommandName(),
          "error",
          event.getElapsedTime(TimeUnit.NANOSECONDS) / 1_000_000.0);
    } catch (RuntimeException e) {
      log.warn("Failed to record mongo command: {}", e.getMessage());
    }
  }

  private void finish(int requestId, String command, String outcome, double durationMs) {
    String collection = collectionByRequest.remove(requestId);
    if (collection != null) {
      telemetry.recordMongoCommand(
          new MongoCommandSample(collection, command, outcome, durationMs));
    }
  }

  @Override
  public void connectionCreated(ConnectionCreatedEvent event) {
    created.incrementAndGet();
  }

  @Override
  public void connectionClosed(ConnectionClosedEvent event) {
    closed.incrementAndGet();
  }

  @Override
  public void connectionCheckedOut(ConnectionCheckedOutEvent event) {
    checkedOut.incrementAndGet();
    try {
      telemetry.recordMongoCheckout(event.getElapsedTime(TimeUnit.NANOSECONDS) / 1_000_000.0);
    } catch (RuntimeException e) {
      log.warn("Failed to record mongo checkout: {}", e.getMessage());
    }
  }

  @Override
  public void connectionCheckedIn(ConnectionCheckedInEvent event) {
    checkedIn.incrementAndGet();
  }

  /** Point-in-time pool occupancy, polled by the pool gauge. */
  public PoolStats poolStats() {
    int inUse = Math.max(0, checkedOut.get() - checkedIn.get());
    int open = Math.max(0, created.get() - closed.get());
    return new PoolStats(inUse, Math.max(0, open - inUse));
  }
}
