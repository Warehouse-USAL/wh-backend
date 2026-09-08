package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.service.VehicleEventPublisher;
import com.usal.whbackend.telemetry.TelemetryPort;
import com.usal.whbackend.telemetry.VehicleStatusChange;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Function;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class VehicleErrorConsumerTest {

  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  @Mock TelemetryPort telemetry;
  VehicleErrorConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer = new VehicleErrorConsumer(vehicleUpdateExecutor, vehicleEventPublisher, telemetry);
  }

  /** Same $set simulation as {@code VehicleTelemetryConsumerTest} — see its javadoc. */
  private static Vehicle applyUpdate(Vehicle previous, Update update) {
    Vehicle copy = new Vehicle();
    copy.setId(previous.getId());
    copy.setStatus(previous.getStatus());
    copy.setPositionX(previous.getPositionX());
    copy.setPositionY(previous.getPositionY());
    copy.setBattery(previous.getBattery());
    copy.setCurrentOrderId(previous.getCurrentOrderId());
    copy.setLastSeenAt(previous.getLastSeenAt());
    copy.setOperationSince(previous.getOperationSince());

    Document set = update.getUpdateObject().get("$set", Document.class);
    if (set != null) {
      if (set.containsKey("status")) {
        copy.setStatus((VehicleStatus) set.get("status"));
      }
      if (set.containsKey("lastSeenAt")) {
        copy.setLastSeenAt((Instant) set.get("lastSeenAt"));
      }
      if (set.containsKey("operationSince")) {
        copy.setOperationSince((Instant) set.get("operationSince"));
      }
    }
    return copy;
  }

  /**
   * Stubs the executor to actually run the consumer's update builder against `previous` (rather
   * than a canned result), and returns a captor over that builder so a test can re-apply it to
   * inspect the resulting Vehicle — needed because, unlike the telemetry consumer, this consumer
   * never hands the updated Vehicle object to the event publisher, only discrete fields.
   */
  @SuppressWarnings("unchecked")
  private ArgumentCaptor<Function<Vehicle, Update>> stubExecutor(
      String vehicleId, Vehicle previous) {
    ArgumentCaptor<Function<Vehicle, Update>> captor = ArgumentCaptor.forClass(Function.class);
    when(vehicleUpdateExecutor.apply(eq(vehicleId), captor.capture()))
        .thenAnswer(
            inv -> {
              Function<Vehicle, Update> builder = inv.getArgument(1);
              Update update = builder.apply(previous);
              Vehicle updated = applyUpdate(previous, update);
              return Optional.of(new VehicleUpdateExecutor.Result(previous.getStatus(), updated));
            });
    return captor;
  }

  @Test
  void consume_setsVehicleOfflineAndBroadcastsError() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.BUSY);
    ArgumentCaptor<Function<Vehicle, Update>> captor = stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "Sin señal desde hace 30 segundos.",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    Vehicle updated = applyUpdate(vehicle, captor.getValue().apply(vehicle));
    assertEquals(VehicleStatus.OFFLINE, updated.getStatus());
    verify(vehicleEventPublisher)
        .broadcastVehicleError(eq("vhc-2"), eq("CONNECTION_LOST"), any(), any());
  }

  @Test
  void consume_movesVehicleOfflineAndClearsOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.BUSY);
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    ArgumentCaptor<Function<Vehicle, Update>> captor = stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "Sin señal desde hace 30 segundos.",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    Vehicle updated = applyUpdate(vehicle, captor.getValue().apply(vehicle));
    assertNull(
        updated.getOperationSince(),
        "a vehicle that just went offline due to an error has no ongoing operation window");
  }

  @Test
  void consume_recordsTransitionWithTheRealErrorCodeAsCategory() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.BUSY);
    stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "connection_lost",
                    "Sin señal desde hace 30 segundos.",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("vhc-2", captor.getValue().vehicleId());
    assertEquals("BUSY", captor.getValue().from());
    assertEquals("OFFLINE", captor.getValue().to());
    assertEquals("CONNECTION_LOST", captor.getValue().category());
  }

  @Test
  void consume_vehicleWithNoPriorStatus_recordsTransitionFromUnknown() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "Sin señal",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("UNKNOWN", captor.getValue().from());
  }

  @Test
  void consume_unrecognizedErrorCodeFallsBackToOther() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.IDLE);
    stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error", "vhc-2", "SOMETHING_NEW", "boom", "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("OTHER", captor.getValue().category());
  }

  @Test
  void consume_recordsNoTransitionWhenAlreadyOffline() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "still gone",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    verify(telemetry, never()).recordStatusTransition(any());
  }

  @Test
  void consume_alreadyOfflineVehicleStillGetsItsOperationWindowCleared() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    // Should already be null in practice, but the update is unconditional and idempotent either
    // way — a re-reported error for an already-offline vehicle must not leave a stale window.
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    ArgumentCaptor<Function<Vehicle, Update>> captor = stubExecutor("vhc-2", vehicle);

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "still gone",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    Vehicle updated = applyUpdate(vehicle, captor.getValue().apply(vehicle));
    assertNull(updated.getOperationSince());
  }

  @Test
  void consume_persistsAndBroadcastsEvenIfTelemetryBlowsUp() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-2");
    vehicle.setStatus(VehicleStatus.BUSY);
    stubExecutor("vhc-2", vehicle);
    doThrow(new IllegalStateException("exporter exploded"))
        .when(telemetry)
        .recordStatusTransition(any());

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error",
                    "vhc-2",
                    "CONNECTION_LOST",
                    "Sin señal",
                    "2026-05-01T10:07:00Z"));

    consumer.consume(message);

    verify(vehicleEventPublisher).broadcastVehicleError(eq("vhc-2"), any(), any(), any());
  }

  @Test
  void consume_malformedPayload_isLoggedAndSwallowed() {
    consumer.consume("not-json");

    verify(vehicleUpdateExecutor, never()).apply(any(), any());
    verify(vehicleEventPublisher, never()).broadcastVehicleError(any(), any(), any(), any());
  }

  @Test
  void consume_unknownVehicle_isIgnored() throws Exception {
    when(vehicleUpdateExecutor.apply(eq("ghost"), any())).thenReturn(Optional.empty());

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleErrorMessage(
                    "vehicle.error", "ghost", "E01", "boom", "2026-05-01T10:00:00Z"));

    consumer.consume(message);

    verify(vehicleEventPublisher, never()).broadcastVehicleError(any(), any(), any(), any());
  }
}
