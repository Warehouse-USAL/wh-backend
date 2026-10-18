package com.usal.whbackend.service.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory ring buffer of the last {@value #CAPACITY} WebSocket events per feed.
 *
 * <p>Listeners (the console push stream) are told about every new event. They run while the feed is
 * locked, the same lock {@link #withRecent} holds, so a reader that takes a snapshot and then
 * starts listening can neither miss an event nor see one twice. Listeners must therefore be quick
 * and must never block; one that throws is skipped.
 */
@Component
public class WsEventBuffer {

  static final int CAPACITY = 200;

  private static final Logger log = LoggerFactory.getLogger(WsEventBuffer.class);

  private final Map<String, Deque<WsEvent>> feeds = new ConcurrentHashMap<>();
  private final Map<String, List<Consumer<WsEvent>>> listeners = new ConcurrentHashMap<>();

  public void record(String feed, WsEvent event) {
    Deque<WsEvent> deque = deque(feed);
    synchronized (deque) {
      deque.addFirst(event);
      if (deque.size() > CAPACITY) {
        deque.removeLast();
      }
      for (Consumer<WsEvent> listener : listeners.getOrDefault(feed, List.of())) {
        try {
          listener.accept(event);
        } catch (RuntimeException e) {
          log.debug("WebSocket event listener failed: {}", e.getClass().getSimpleName());
        }
      }
    }
  }

  /** Newest first. */
  public List<WsEvent> recent(String feed) {
    Deque<WsEvent> deque = feeds.get(feed);
    if (deque == null) {
      return List.of();
    }
    synchronized (deque) {
      return new ArrayList<>(deque);
    }
  }

  /** Runs {@code action} with the current contents (newest first) while no event can be added. */
  public void withRecent(String feed, Consumer<List<WsEvent>> action) {
    Deque<WsEvent> deque = deque(feed);
    synchronized (deque) {
      action.accept(new ArrayList<>(deque));
    }
  }

  public void addListener(String feed, Consumer<WsEvent> listener) {
    listeners.computeIfAbsent(feed, f -> new CopyOnWriteArrayList<>()).add(listener);
  }

  public void removeListener(String feed, Consumer<WsEvent> listener) {
    List<Consumer<WsEvent>> list = listeners.get(feed);
    if (list != null) {
      list.remove(listener);
    }
  }

  private Deque<WsEvent> deque(String feed) {
    return feeds.computeIfAbsent(feed, f -> new ArrayDeque<>());
  }
}
