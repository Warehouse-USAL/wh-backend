package com.usal.whbackend.telemetry;

/**
 * Records domain telemetry. The wrapper the rest of the codebase talks to, so no other class needs
 * to know OpenTelemetry exists.
 *
 * <p>Implementations MUST NOT throw. Telemetry is observational: losing a sample is acceptable,
 * failing the request or the Kafka message that produced it is not.
 */
public interface TelemetryPort {

  /** Records a vehicle telemetry reading. Never throws. */
  void recordVehicleSample(VehicleSample sample);

  /**
   * Records that a vehicle changed status. Never throws.
   *
   * <p>Only actual changes should be passed: the counter it feeds is the denominator of every
   * failure-rate the dashboard derives, so re-recording an unchanged status would inflate them.
   */
  void recordStatusTransition(VehicleStatusChange change);

  /** Records one finished HTTP request. Never throws. */
  void recordHttpRequest(HttpRequestSample sample);

  /** Records one finished MongoDB command. Never throws. */
  void recordMongoCommand(MongoCommandSample sample);

  /** Records how long a MongoDB connection checkout waited, in ms. Never throws. */
  void recordMongoCheckout(double durationMs);

  /**
   * Records one Kafka message on {@code topic}; {@code direction} is {@code out} or {@code in}.
   * Never throws.
   */
  void recordKafkaMessage(String topic, String direction, boolean ok);

  /** Moves the open-session count of a WebSocket feed by {@code delta}. Never throws. */
  void recordWsSession(String feed, int delta);

  /**
   * Records one WebSocket message on a feed; {@code result} is {@code pushed}, {@code rejected} or
   * {@code closed}. Never throws.
   */
  void recordWsMessage(String feed, String result);
}
