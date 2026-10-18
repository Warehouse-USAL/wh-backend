package com.usal.whbackend.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only class in the codebase that imports {@code io.opentelemetry.*}. Enforced by {@code
 * ArchitectureTest.openTelemetryIsConfinedToTheTelemetryPackage}.
 *
 * <p>Publishes raw fleet state and nothing derived. MTBF, MTTR and failure rates are ratios of
 * these series, computed by whoever is drawing the chart — see the design spec's division of
 * labour. Deriving them here would mean owning somebody else's definitions.
 */
public class OtelTelemetryAdapter implements TelemetryPort {

  private static final Logger log = LoggerFactory.getLogger(OtelTelemetryAdapter.class);

  /** Catalogue name. Stored by VictoriaMetrics as {@code wh_vehicle_battery}. */
  public static final String BATTERY_METRIC = "wh.vehicle.battery";

  /** Catalogue name for the fleet state-set gauge. */
  public static final String STATE_METRIC = "wh.vehicle.state";

  /** Catalogue name for the status-transition counter. */
  public static final String TRANSITIONS_METRIC = "wh.vehicle.transitions";

  /** Catalogue name for the HTTP server latency histogram. */
  public static final String HTTP_METRIC = "http.server.duration";

  /** Catalogue name for the Mongo command latency histogram. */
  public static final String MONGO_COMMAND_METRIC = "mongo.command.duration";

  /** Catalogue name for the Mongo connection checkout wait histogram. */
  public static final String MONGO_CHECKOUT_METRIC = "mongo.pool.checkout";

  /** Catalogue name for the Mongo pool occupancy gauge. */
  public static final String MONGO_POOL_METRIC = "mongo.pool.connections";

  /** Catalogue name for the Kafka message counter. */
  public static final String KAFKA_METRIC = "kafka.messages";

  /** Catalogue name for the open WebSocket sessions gauge. */
  public static final String WS_SESSIONS_METRIC = "ws.sessions";

  /** Catalogue name for the WebSocket message counter. */
  public static final String WS_MESSAGES_METRIC = "ws.messages";

  private static final List<Double> MS_BUCKETS =
      List.of(5.0, 10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0, 2500.0, 5000.0, 10000.0);

  static final AttributeKey<String> COLLECTION = AttributeKey.stringKey("collection");
  static final AttributeKey<String> COMMAND = AttributeKey.stringKey("command");
  static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
  static final AttributeKey<String> ROUTE = AttributeKey.stringKey("route");
  static final AttributeKey<String> METHOD = AttributeKey.stringKey("method");
  static final AttributeKey<String> STATUS_CLASS = AttributeKey.stringKey("status_class");
  static final AttributeKey<String> VEHICLE_ID = AttributeKey.stringKey("vehicle_id");
  static final AttributeKey<String> STATE = AttributeKey.stringKey("state");
  static final AttributeKey<String> FROM = AttributeKey.stringKey("from");
  static final AttributeKey<String> TO = AttributeKey.stringKey("to");
  static final AttributeKey<String> CATEGORY = AttributeKey.stringKey("category");
  static final AttributeKey<String> TOPIC = AttributeKey.stringKey("topic");
  static final AttributeKey<String> DIRECTION = AttributeKey.stringKey("direction");
  static final AttributeKey<String> RESULT = AttributeKey.stringKey("result");
  static final AttributeKey<String> FEED = AttributeKey.stringKey("feed");

  private final DoubleGauge batteryGauge;
  private final LongCounter transitionCounter;
  private final DoubleHistogram httpHistogram;
  private final DoubleHistogram mongoCommandHistogram;
  private final DoubleHistogram mongoCheckoutHistogram;
  private final LongCounter kafkaCounter;
  private final LongCounter wsMessageCounter;
  private final Map<String, AtomicInteger> wsSessions = new ConcurrentHashMap<>();
  private final Meter meter;

  public OtelTelemetryAdapter(Meter meter, FleetStateSource fleetStateSource) {
    this.meter = meter;
    this.batteryGauge =
        meter
            .gaugeBuilder(BATTERY_METRIC)
            .setUnit("%")
            .setDescription("Vehicle battery charge level, 0-100")
            .build();

    this.transitionCounter =
        meter
            .counterBuilder(TRANSITIONS_METRIC)
            .setUnit("1")
            .setDescription("Vehicle status transitions, counted once per observed change")
            .build();

    this.httpHistogram =
        meter
            .histogramBuilder(HTTP_METRIC)
            .setUnit("ms")
            .setDescription("HTTP server request duration")
            .setExplicitBucketBoundariesAdvice(MS_BUCKETS)
            .build();

    this.mongoCommandHistogram =
        meter
            .histogramBuilder(MONGO_COMMAND_METRIC)
            .setUnit("ms")
            .setDescription("MongoDB command duration")
            .setExplicitBucketBoundariesAdvice(MS_BUCKETS)
            .build();

    this.mongoCheckoutHistogram =
        meter
            .histogramBuilder(MONGO_CHECKOUT_METRIC)
            .setUnit("ms")
            .setDescription("Time spent waiting for a MongoDB connection from the pool")
            .setExplicitBucketBoundariesAdvice(MS_BUCKETS)
            .build();

    this.kafkaCounter =
        meter
            .counterBuilder(KAFKA_METRIC)
            .setUnit("1")
            .setDescription("Kafka messages by topic, direction and result")
            .build();

    this.wsMessageCounter =
        meter
            .counterBuilder(WS_MESSAGES_METRIC)
            .setUnit("1")
            .setDescription("WebSocket messages by feed and result")
            .build();

    registerStateGauge(meter, fleetStateSource);
    registerWsSessionsGauge(meter);
  }

  /** Observable, so a feed whose sessions all closed keeps reporting an explicit 0. */
  private void registerWsSessionsGauge(Meter meter) {
    meter
        .gaugeBuilder(WS_SESSIONS_METRIC)
        .ofLongs()
        .setUnit("1")
        .setDescription("Open WebSocket sessions by feed")
        .buildWithCallback(
            measurement -> {
              try {
                wsSessions.forEach(
                    (feed, count) -> measurement.record(count.get(), Attributes.of(FEED, feed)));
              } catch (RuntimeException e) {
                log.warn("Failed to observe websocket sessions: {}", e.getMessage());
              }
            });
  }

  /**
   * An <em>observable</em> gauge, deliberately: it is polled once per export interval rather than
   * written when a message arrives.
   *
   * <p>A synchronous gauge only updates on message arrival, so a vehicle that stops publishing
   * keeps exporting its last value indefinitely — summing those to count active rovers would count
   * dead ones forever. Reading the whole fleet each tick means the published series set is always
   * exactly the current fleet.
   *
   * <p>The returned handle is not retained: {@code SdkMeterProvider} holds the callback for its own
   * lifetime, and the SDK bean is closed on shutdown.
   */
  private void registerStateGauge(Meter meter, FleetStateSource fleetStateSource) {
    meter
        .gaugeBuilder(STATE_METRIC)
        .setUnit("1")
        .setDescription("1 when the vehicle is in that state, 0 otherwise")
        .buildWithCallback(
            measurement -> {
              try {
                List<String> states = fleetStateSource.knownStates();
                for (VehicleState vehicle : fleetStateSource.currentFleet()) {
                  for (String state : states) {
                    measurement.record(
                        state.equals(vehicle.status()) ? 1 : 0,
                        Attributes.of(VEHICLE_ID, vehicle.vehicleId(), STATE, state));
                  }
                }
              } catch (RuntimeException e) {
                // Runs on the SDK's export thread. Throwing here would kill the export, taking
                // every other metric down with it.
                log.warn("Failed to observe fleet state: {}", e.getMessage());
              }
            });
  }

  /**
   * Publishes the Mongo pool occupancy as an observable gauge. Not on {@link TelemetryPort}: the
   * stats source is the driver listener, which only exists once the Mongo client is being built, so
   * the wiring calls this on the concrete adapter.
   */
  public void registerPoolGauge(Supplier<MongoMetricsListener.PoolStats> stats) {
    meter
        .gaugeBuilder(MONGO_POOL_METRIC)
        .setUnit("1")
        .setDescription("MongoDB pool connections by state")
        .buildWithCallback(
            measurement -> {
              try {
                MongoMetricsListener.PoolStats current = stats.get();
                measurement.record(current.inUse(), Attributes.of(STATE, "in_use"));
                measurement.record(current.available(), Attributes.of(STATE, "available"));
              } catch (RuntimeException e) {
                log.warn("Failed to observe mongo pool: {}", e.getMessage());
              }
            });
  }

  @Override
  public void recordVehicleSample(VehicleSample sample) {
    if (sample == null || sample.vehicleId() == null || sample.vehicleId().isBlank()) {
      return;
    }
    try {
      batteryGauge.set(sample.batteryPercent(), Attributes.of(VEHICLE_ID, sample.vehicleId()));
    } catch (RuntimeException e) {
      // Telemetry is observational — never propagate. See TelemetryPort's contract.
      log.warn(
          "Failed to record battery telemetry for vehicle {}: {}",
          sample.vehicleId(),
          e.getMessage());
    }
  }

  @Override
  public void recordStatusTransition(VehicleStatusChange change) {
    if (change == null || change.vehicleId() == null || change.vehicleId().isBlank()) {
      return;
    }
    try {
      transitionCounter.add(
          1,
          Attributes.builder()
              .put(VEHICLE_ID, change.vehicleId())
              .put(FROM, nullSafe(change.from()))
              .put(TO, nullSafe(change.to()))
              .put(CATEGORY, nullSafe(change.category()))
              .build());
    } catch (RuntimeException e) {
      log.warn(
          "Failed to record status transition for vehicle {}: {}",
          change.vehicleId(),
          e.getMessage());
    }
  }

  @Override
  public void recordHttpRequest(HttpRequestSample sample) {
    if (sample == null) {
      return;
    }
    try {
      httpHistogram.record(
          sample.durationMs(),
          Attributes.of(
              ROUTE,
              nullSafe(sample.route()),
              METHOD,
              nullSafe(sample.method()),
              STATUS_CLASS,
              (sample.status() / 100) + "xx"));
    } catch (RuntimeException e) {
      log.warn("Failed to record HTTP request duration: {}", e.getMessage());
    }
  }

  @Override
  public void recordMongoCommand(MongoCommandSample sample) {
    if (sample == null) {
      return;
    }
    try {
      mongoCommandHistogram.record(
          sample.durationMs(),
          Attributes.of(
              COLLECTION,
              nullSafe(sample.collection()),
              COMMAND,
              nullSafe(sample.command()),
              OUTCOME,
              nullSafe(sample.outcome())));
    } catch (RuntimeException e) {
      log.warn("Failed to record mongo command duration: {}", e.getMessage());
    }
  }

  @Override
  public void recordMongoCheckout(double durationMs) {
    try {
      mongoCheckoutHistogram.record(durationMs);
    } catch (RuntimeException e) {
      log.warn("Failed to record mongo checkout duration: {}", e.getMessage());
    }
  }

  @Override
  public void recordKafkaMessage(String topic, String direction, boolean ok) {
    try {
      kafkaCounter.add(
          1,
          Attributes.of(
              TOPIC, nullSafe(topic), DIRECTION, nullSafe(direction), RESULT, ok ? "ok" : "error"));
    } catch (RuntimeException e) {
      log.warn("Failed to record kafka message: {}", e.getMessage());
    }
  }

  @Override
  public void recordWsSession(String feed, int delta) {
    try {
      wsSessions.computeIfAbsent(nullSafe(feed), f -> new AtomicInteger()).addAndGet(delta);
    } catch (RuntimeException e) {
      log.warn("Failed to record websocket session: {}", e.getMessage());
    }
  }

  @Override
  public void recordWsMessage(String feed, String result) {
    try {
      wsMessageCounter.add(1, Attributes.of(FEED, nullSafe(feed), RESULT, nullSafe(result)));
    } catch (RuntimeException e) {
      log.warn("Failed to record websocket message: {}", e.getMessage());
    }
  }

  /** A null label would be dropped by the SDK, silently changing the series identity. */
  private static String nullSafe(String value) {
    return value == null || value.isBlank() ? "UNKNOWN" : value;
  }
}
