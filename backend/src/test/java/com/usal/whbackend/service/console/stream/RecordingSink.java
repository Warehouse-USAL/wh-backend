package com.usal.whbackend.service.console.stream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Collects the frames a session writes; can be told to block or fail like a real client. */
class RecordingSink implements FrameSink {

  private final List<String> frames = new ArrayList<>();
  private volatile CountDownLatch gate;
  private volatile boolean failing;
  private volatile boolean completed;
  private volatile Runnable onClose;

  synchronized List<String> frames() {
    return new ArrayList<>(frames);
  }

  synchronized String text() {
    return String.join("", frames);
  }

  /** Blocks every send until {@link #release()}. */
  void block() {
    gate = new CountDownLatch(1);
  }

  void release() {
    CountDownLatch g = gate;
    gate = null;
    if (g != null) {
      g.countDown();
    }
  }

  void fail() {
    failing = true;
  }

  boolean completed() {
    return completed;
  }

  /** Simulates the container noticing the client went away. */
  void clientGone() {
    Runnable r = onClose;
    if (r != null) {
      r.run();
    }
  }

  @Override
  public void send(String frame) throws IOException {
    CountDownLatch g = gate;
    if (g != null) {
      try {
        g.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failing) {
      throw new IOException("broken pipe");
    }
    synchronized (this) {
      frames.add(frame);
    }
  }

  @Override
  public void complete() {
    completed = true;
  }

  @Override
  public void onClose(Runnable callback) {
    onClose = callback;
  }
}
