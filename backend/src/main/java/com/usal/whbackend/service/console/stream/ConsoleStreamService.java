package com.usal.whbackend.service.console.stream;

import com.usal.whbackend.config.RequestIdFilter;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.console.ConsoleProperties;
import com.usal.whbackend.service.console.LogsQueryService;
import com.usal.whbackend.service.console.ServiceStatusService;
import com.usal.whbackend.service.console.WsEventBuffer;
import com.usal.whbackend.service.metrics.MetricsQueryService;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The console's push stream ({@code POST /console/stream}): one Server-Sent Events response per
 * open console view, multiplexing metric, logs, WebSocket-event and status subscriptions.
 *
 * <p>Threads: nothing runs on a request thread after the handler returns. One scheduler thread
 * dispatches ticks, heartbeats and token expiries; a small fixed pool evaluates polled sources
 * (VictoriaMetrics, VictoriaLogs and the status probes block on HTTP); writers to clients run on a
 * cached pool with at most one writer per stream, so a slow client only ever blocks its own writer.
 * Platform threads: the project does not enable virtual threads, and blocking writes and {@code
 * HttpURLConnection} reads would pin them on Java 21 anyway.
 *
 * <p>A stream ends with {@code event: end} when its token expires ({@code token-expired}), when the
 * server shuts down ({@code server-shutdown}, before the web server's graceful shutdown waits on
 * open requests), when its client cannot keep up ({@code limit}), or when its principal opens more
 * streams than {@code console.stream.max-streams-per-principal} and it is the principal's oldest
 * ({@code limit}). A client that stops reading for {@code console.stream.send-timeout} is dropped;
 * one that disconnects is noticed by the container or by the first write that fails (heartbeats
 * guarantee one every 15 s; a half-open connection only fails once the kernel gives up
 * retransmitting). Either way every source it held is released at once.
 *
 * <p>Only the global cap ({@code console.stream.max-streams}) refuses a new stream with 429.
 */
@Service
public class ConsoleStreamService implements SmartLifecycle {

  static final String TOKEN_EXPIRED = "token-expired";
  static final String SERVER_SHUTDOWN = "server-shutdown";
  static final String SEND_TIMEOUT = "send-timeout";
  static final String STREAM_LIMIT = "STREAM_LIMIT";

  private static final int EVALUATORS = 8;
  private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(2);

  /** The emitter outlives the token slightly, so the expiry frame is what ends the stream. */
  private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(1);

  private static final Logger log = LoggerFactory.getLogger(ConsoleStreamService.class);

  private final ConsoleProperties.Stream config;
  private final StreamRequestParser parser;
  private final SourceRegistry registry;
  private final ScheduledThreadPoolExecutor scheduler;
  private final ExecutorService evaluators;
  private final ExecutorService writers;
  private final Set<OpenStream> streams = ConcurrentHashMap.newKeySet();

  // Guarded by this. Each principal's open streams, oldest first.
  private final Map<String, Deque<OpenStream>> perPrincipal = new HashMap<>();
  private int total;

  private volatile boolean running;
  private volatile ScheduledFuture<?> heartbeat;

  public ConsoleStreamService(
      ConsoleProperties properties,
      MetricsQueryService metrics,
      LogsQueryService logs,
      ServiceStatusService status,
      WsEventBuffer buffer,
      JsonMapper json) {
    this.config = properties.getStream();
    this.parser = new StreamRequestParser(logs, config.getMaxSubscriptions());
    this.scheduler = new ScheduledThreadPoolExecutor(1, daemon("console-stream-scheduler"));
    this.scheduler.setRemoveOnCancelPolicy(true);
    this.evaluators = Executors.newFixedThreadPool(EVALUATORS, daemon("console-stream-source"));
    this.writers = Executors.newCachedThreadPool(daemon("console-stream-writer"));
    Clock clock = Clock.systemUTC();
    this.registry =
        new SourceRegistry(
            key ->
                switch (key) {
                  case SourceKey.Metric m -> new MetricSource(m, metrics, json, clock);
                  case SourceKey.Logs l ->
                      new LogsSource(l, logs, json, clock, config.getLogsOverlap());
                  case SourceKey.WsEvents w -> new WsEventsSource(w, buffer, json);
                  case SourceKey.Status s -> new StatusSource(status, json);
                },
            key ->
                switch (key) {
                  case SourceKey.Metric m -> config.getMetricInterval();
                  case SourceKey.Logs l -> config.getLogsInterval();
                  case SourceKey.Status s -> config.getStatusInterval();
                  case SourceKey.WsEvents w -> null;
                },
            scheduler,
            evaluators);
  }

  /**
   * Validates the request (400 before anything opens), checks the global stream cap (429 {@code
   * STREAM_LIMIT}) and returns the response body. A principal at its own limit has its oldest
   * stream ended with {@code limit} instead. {@code expiresAt} is the token's expiry, after which
   * the stream ends; {@code principal} is only used for counting.
   */
  public ResponseBodyEmitter open(
      StreamRequest request,
      String principal,
      Set<UserRole> roles,
      Instant expiresAt,
      String requestId) {
    long timeout =
        expiresAt == null
            ? 0L
            : Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis())
                + EXPIRY_MARGIN.toMillis();
    ResponseBodyEmitter emitter = new ResponseBodyEmitter(timeout);
    open(request, principal, roles, expiresAt, requestId, new EmitterSink(emitter));
    return emitter;
  }

  StreamSession open(
      StreamRequest request,
      String principal,
      Set<UserRole> roles,
      Instant expiresAt,
      String requestId,
      FrameSink sink) {
    List<ParsedSubscription> subscriptions = parser.parse(request, roles);
    OpenStream stream = new OpenStream(principal, requestId, subscriptions.size());
    StreamSession session =
        new StreamSession(sink, config.getQueueCapacity(), writers, s -> closed(stream, s));
    stream.session = session;
    OpenStream evicted = reserve(stream);
    streams.add(stream);
    if (evicted != null) {
      // Outside the lock: ending only queues the goodbye, and the close callback releases the
      // evicted stream's sources and count exactly once.
      evicted.session.end(StreamSession.LIMIT);
    }
    synchronized (stream) {
      for (ParsedSubscription sub : subscriptions) {
        if (stream.released) {
          break;
        }
        Subscriber subscriber = new Subscriber(session, sub.id());
        registry.acquire(sub.key(), subscriber);
        stream.acquired.add(Map.entry(sub.key(), subscriber));
      }
    }
    if (expiresAt != null) {
      long delay = Math.max(0, Duration.between(Instant.now(), expiresAt).toMillis());
      stream.expiry =
          scheduler.schedule(() -> session.end(TOKEN_EXPIRED), delay, TimeUnit.MILLISECONDS);
    }
    log.info(
        "Console stream opened: {} subscriptions, {} streams open",
        subscriptions.size(),
        streams.size());
    return session;
  }

  /** Streams currently open. */
  int openStreams() {
    return streams.size();
  }

  /** Distinct sources currently evaluated or listening. */
  int activeSources() {
    return registry.size();
  }

  @Override
  public void start() {
    long every = config.getHeartbeat().toMillis();
    heartbeat =
        scheduler.scheduleWithFixedDelay(this::heartbeat, every, every, TimeUnit.MILLISECONDS);
    running = true;
  }

  /**
   * Ends every stream with {@code server-shutdown} and waits briefly for the goodbyes to go out.
   */
  @Override
  public void stop() {
    running = false;
    ScheduledFuture<?> beat = heartbeat;
    if (beat != null) {
      beat.cancel(false);
    }
    List<StreamSession> ending = streams.stream().map(s -> s.session).toList();
    for (StreamSession session : ending) {
      session.end(SERVER_SHUTDOWN);
    }
    long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
    while (!ending.stream().allMatch(StreamSession::isDone) && System.nanoTime() < deadline) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @PreDestroy
  void destroy() {
    if (running) {
      stop();
    }
    scheduler.shutdownNow();
    evaluators.shutdownNow();
    writers.shutdownNow();
  }

  /**
   * Counts the stream against the global cap and its principal. At the principal's limit the
   * principal's oldest stream leaves the count and is returned for the caller to end outside this
   * lock; it stays in {@code total} until its close callback runs.
   */
  private synchronized OpenStream reserve(OpenStream stream) {
    if (total >= config.getMaxStreams()) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, STREAM_LIMIT);
    }
    Deque<OpenStream> mine =
        perPrincipal.computeIfAbsent(stream.principal, p -> new ArrayDeque<>());
    OpenStream evicted =
        mine.size() >= config.getMaxStreamsPerPrincipal() ? mine.pollFirst() : null;
    mine.addLast(stream);
    total++;
    return evicted;
  }

  private synchronized void unreserve(OpenStream stream) {
    Deque<OpenStream> mine = perPrincipal.get(stream.principal);
    if (mine != null) {
      mine.remove(stream);
      if (mine.isEmpty()) {
        perPrincipal.remove(stream.principal);
      }
    }
    total--;
  }

  private void heartbeat() {
    long now = System.nanoTime();
    long limit = config.getSendTimeout().toNanos();
    for (OpenStream stream : streams) {
      long since = stream.session.sendingSinceNanos();
      if (since != 0 && now - since > limit) {
        stream.session.close(SEND_TIMEOUT);
      } else {
        stream.session.ping();
      }
    }
  }

  private void closed(OpenStream stream, StreamSession session) {
    synchronized (stream) {
      stream.released = true;
      for (Map.Entry<SourceKey, Subscriber> held : stream.acquired) {
        registry.release(held.getKey(), held.getValue());
      }
      stream.acquired.clear();
    }
    ScheduledFuture<?> expiry = stream.expiry;
    if (expiry != null) {
      expiry.cancel(false);
    }
    if (streams.remove(stream)) {
      unreserve(stream);
    }
    String previous = MDC.get(RequestIdFilter.MDC_KEY);
    if (stream.requestId != null) {
      MDC.put(RequestIdFilter.MDC_KEY, stream.requestId);
    }
    try {
      log.info(
          "Console stream closed ({}): {} subscriptions, {}s",
          session.closeReason(),
          stream.subscriptions,
          Duration.between(stream.openedAt, Instant.now()).toSeconds());
    } finally {
      if (previous == null) {
        MDC.remove(RequestIdFilter.MDC_KEY);
      } else {
        MDC.put(RequestIdFilter.MDC_KEY, previous);
      }
    }
  }

  private static ThreadFactory daemon(String name) {
    AtomicInteger n = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, name + "-" + n.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  /** Book-keeping for one open stream. */
  private static final class OpenStream {
    final String principal;
    final String requestId;
    final int subscriptions;
    final Instant openedAt = Instant.now();
    final List<Map.Entry<SourceKey, Subscriber>> acquired = new ArrayList<>();
    volatile StreamSession session;
    volatile ScheduledFuture<?> expiry;
    boolean released;

    OpenStream(String principal, String requestId, int subscriptions) {
      this.principal = principal;
      this.requestId = requestId;
      this.subscriptions = subscriptions;
    }
  }
}
