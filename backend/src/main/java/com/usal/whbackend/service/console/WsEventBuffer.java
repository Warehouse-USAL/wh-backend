package com.usal.whbackend.service.console;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** In-memory ring buffer of the last {@value #CAPACITY} WebSocket events per feed. */
@Component
public class WsEventBuffer {

  static final int CAPACITY = 200;

  private final Map<String, Deque<WsEvent>> feeds = new ConcurrentHashMap<>();

  public void record(String feed, WsEvent event) {
    Deque<WsEvent> deque = feeds.computeIfAbsent(feed, f -> new ArrayDeque<>());
    synchronized (deque) {
      deque.addFirst(event);
      if (deque.size() > CAPACITY) {
        deque.removeLast();
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
}
