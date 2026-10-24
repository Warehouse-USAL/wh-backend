package com.usal.whbackend.service.console.stream;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/**
 * One open stream: a bounded queue of frames and a single writer draining it to the client.
 *
 * <p>Producers (sources, the heartbeat) only ever enqueue, so they never block on a slow client.
 * Backpressure is per stream: a pending {@code replace} frame (metric, status) is overwritten by a
 * newer one for the same subscription, so it never grows; an {@code append} frame (logs, WebSocket
 * events) cannot be dropped without breaking "every line once", so when the queue is full the
 * stream is ended with {@code end{reason:"limit"}} and the client resubscribes. The writer runs on
 * {@code executor}; at most one writer per session is ever active.
 */
final class StreamSession {

  static final String CLIENT_GONE = "client-gone";
  static final String LIMIT = "limit";

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String PING = ": ping\n\n";

  private final FrameSink sink;
  private final int capacity;
  private final Executor executor;
  private final Consumer<StreamSession> onClosed;
  private final Object lock = new Object();
  private final AtomicBoolean closedFired = new AtomicBoolean();

  // Guarded by lock.
  private final Deque<Frame> queue = new ArrayDeque<>();
  private final Map<String, Frame> pendingReplace = new HashMap<>();
  private boolean open = true;
  private boolean draining;
  private String closeReason;

  private volatile long sendingSince;
  private volatile boolean done;

  StreamSession(FrameSink sink, int capacity, Executor executor, Consumer<StreamSession> onClosed) {
    this.sink = sink;
    this.capacity = capacity;
    this.executor = executor;
    this.onClosed = onClosed;
    sink.onClose(() -> close(CLIENT_GONE));
  }

  /**
   * Queues a data frame. {@code snapshot} picks the event name ({@code snapshot} for the first
   * frame of a subscription, {@code update} after); {@code replace} picks the mode.
   */
  void offerData(String id, boolean snapshot, boolean replace, String json) {
    String event = snapshot ? "snapshot" : "update";
    String mode = replace ? "replace" : "append";
    boolean overflowed;
    synchronized (lock) {
      if (!open) {
        return;
      }
      if (replace) {
        Frame pending = pendingReplace.get(id);
        if (pending != null) {
          pending.data = json;
          return;
        }
      }
      Frame frame = new Frame(id, event, mode, json);
      overflowed = !enqueue(frame);
      if (replace && !overflowed) {
        pendingReplace.put(id, frame);
      }
    }
    afterOffer(overflowed);
  }

  void offerError(String id, String code, String message) {
    String text =
        "event: error\ndata: {\"id\":"
            + quote(id)
            + ",\"code\":"
            + quote(code)
            + ",\"message\":"
            + quote(message)
            + "}\n\n";
    boolean overflowed;
    synchronized (lock) {
      if (!open) {
        return;
      }
      // A replace queued before the error must not be refreshed with data newer than the error.
      pendingReplace.remove(id);
      overflowed = !enqueue(new Frame(text));
    }
    afterOffer(overflowed);
  }

  private void afterOffer(boolean overflowed) {
    if (overflowed) {
      fireClosed();
    }
    drainLater();
  }

  /** Keep-alive comment, skipped while frames are already on their way. */
  void ping() {
    synchronized (lock) {
      if (!open || draining || !queue.isEmpty()) {
        return;
      }
      queue.add(new Frame(PING));
    }
    drainLater();
  }

  /** Sends {@code end{reason}} after dropping anything still queued, then completes. */
  void end(String reason) {
    synchronized (lock) {
      if (!endLocked(reason)) {
        return;
      }
    }
    fireClosed();
    drainLater();
  }

  /** Closes without a goodbye frame (the client is gone or stuck). */
  void close(String reason) {
    synchronized (lock) {
      if (!open) {
        return;
      }
      open = false;
      closeReason = reason;
      queue.clear();
      pendingReplace.clear();
    }
    done = true;
    // Off the caller's thread: completing waits for a write in progress, and the caller may be
    // the shared scheduler giving up on a stuck client.
    executor.execute(sink::complete);
    fireClosed();
  }

  /** True once nothing more will be written: the end frame went out or the session was closed. */
  boolean isDone() {
    return done;
  }

  boolean isOpen() {
    synchronized (lock) {
      return open;
    }
  }

  String closeReason() {
    synchronized (lock) {
      return closeReason;
    }
  }

  /** {@link System#nanoTime()} at which the write in progress started, or 0 when idle. */
  long sendingSinceNanos() {
    return sendingSince;
  }

  /** Caller holds the lock. False when the frame overflowed the queue and ended the stream. */
  private boolean enqueue(Frame frame) {
    if (queue.size() >= capacity) {
      endLocked(LIMIT);
      return false;
    }
    queue.add(frame);
    return true;
  }

  /** Caller holds the lock. */
  private boolean endLocked(String reason) {
    if (!open) {
      return false;
    }
    open = false;
    closeReason = reason;
    queue.clear();
    pendingReplace.clear();
    queue.add(new Frame("event: end\ndata: {\"reason\":" + quote(reason) + "}\n\n", true));
    return true;
  }

  private void drainLater() {
    synchronized (lock) {
      if (draining || queue.isEmpty()) {
        return;
      }
      draining = true;
    }
    executor.execute(this::drain);
  }

  private void drain() {
    while (true) {
      String text;
      boolean last;
      synchronized (lock) {
        Frame frame = queue.poll();
        if (frame == null) {
          draining = false;
          return;
        }
        if (frame.id != null) {
          pendingReplace.remove(frame.id, frame);
        }
        text = frame.render();
        last = frame.last;
        sendingSince = System.nanoTime();
      }
      try {
        sink.send(text);
      } catch (IOException | RuntimeException e) {
        sendingSince = 0;
        synchronized (lock) {
          draining = false;
        }
        close(CLIENT_GONE);
        return;
      }
      sendingSince = 0;
      if (last) {
        sink.complete();
        synchronized (lock) {
          draining = false;
        }
        done = true;
        return;
      }
    }
  }

  /** Runs the close callback exactly once, off the caller's locks. */
  private void fireClosed() {
    if (closedFired.compareAndSet(false, true)) {
      executor.execute(() -> onClosed.accept(this));
    }
  }

  private static String quote(String value) {
    return JSON.writeValueAsString(value);
  }

  /** A queued frame; a data frame's payload may be replaced while it waits. */
  private static final class Frame {
    final String id;
    final String event;
    final String mode;
    final String text;
    final boolean last;
    String data;

    Frame(String id, String event, String mode, String data) {
      this.id = id;
      this.event = event;
      this.mode = mode;
      this.data = data;
      this.text = null;
      this.last = false;
    }

    Frame(String text) {
      this(text, false);
    }

    Frame(String text, boolean last) {
      this.id = null;
      this.event = null;
      this.mode = null;
      this.data = null;
      this.text = text;
      this.last = last;
    }

    String render() {
      if (text != null) {
        return text;
      }
      return "event: "
          + event
          + "\ndata: {\"id\":"
          + quote(id)
          + ",\"mode\":\""
          + mode
          + "\",\"data\":"
          + data
          + "}\n\n";
    }
  }
}
