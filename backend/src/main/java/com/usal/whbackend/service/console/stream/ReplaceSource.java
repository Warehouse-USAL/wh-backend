package com.usal.whbackend.service.console.stream;

/**
 * A polled source whose every result replaces the previous one (metric, status). A result is sent
 * only when it differs from the last one sent, compared on {@link Result#compareKey()}.
 */
abstract class ReplaceSource extends Source {

  /** {@code json} is sent; {@code compareKey} decides whether it changed. */
  record Result(String json, String compareKey) {}

  // Guarded by lock.
  private String json;
  private String compareKey;

  /** Runs outside the lock; may block on the backing store. */
  protected abstract Result fetch();

  @Override
  void evaluate() {
    if (failedPermanently()) {
      return;
    }
    Result result;
    try {
      result = fetch();
    } catch (RuntimeException e) {
      failed(e);
      return;
    }
    synchronized (lock) {
      recovered();
      if (result.compareKey().equals(compareKey)) {
        return;
      }
      json = result.json();
      compareKey = result.compareKey();
      for (Subscriber s : subscribers) {
        s.data(true, json);
      }
    }
  }

  @Override
  protected boolean onJoin(Subscriber subscriber) {
    if (json == null) {
      return false;
    }
    subscriber.data(true, json);
    return true;
  }

  @Override
  protected void onFailure() {
    json = null;
    compareKey = null;
  }
}
