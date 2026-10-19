package com.usal.whbackend.telemetry;

/** Discards every sample. Used when {@code telemetry.enabled=false}, and throughout the tests. */
public class NoOpTelemetryAdapter implements TelemetryPort {

  @Override
  public void recordVehicleSample(VehicleSample sample) {
    // intentionally empty
  }

  @Override
  public void recordStatusTransition(VehicleStatusChange change) {
    // intentionally empty
  }

  @Override
  public void recordHttpRequest(HttpRequestSample sample) {
    // intentionally empty
  }

  @Override
  public void recordMongoCommand(MongoCommandSample sample) {
    // intentionally empty
  }

  @Override
  public void recordMongoCheckout(double durationMs) {
    // intentionally empty
  }

  @Override
  public void recordMongoCheckoutFailed(String reason) {
    // intentionally empty
  }

  @Override
  public void recordKafkaMessage(String topic, String direction, boolean ok) {
    // intentionally empty
  }

  @Override
  public void recordKafkaPublish(String topic, boolean ok, double durationMs) {
    // intentionally empty
  }

  @Override
  public void recordWsSession(String feed, int delta) {
    // intentionally empty
  }

  @Override
  public void recordWsMessage(String feed, String result) {
    // intentionally empty
  }
}
