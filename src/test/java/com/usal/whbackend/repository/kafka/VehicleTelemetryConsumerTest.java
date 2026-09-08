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
import com.usal.whbackend.telemetry.VehicleSample;
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
class VehicleTelemetryConsumerTest {

  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  @Mock TelemetryPort telemetry;
  VehicleTelemetryConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new VehicleTelemetryConsumer(vehicleUpdateExecutor, vehicleEventPublisher, telemetry);
  }

  private static String message(String vehicleId, int battery) throws Exception {
    return message(vehicleId, battery, "busy", "2026-05-01T10:00:00Z");
  }

  private static String message(String vehicleId, int battery, String status, String timestamp)
      throws Exception {
    return new ObjectMapper()
        .writeValueAsString(
            new VehicleTelemetryMessage(
                "vehicle.telemetry",
                vehicleId,
                new VehicleTelemetryMessage.Position(14.2, 9.1),
                battery,
                status,
                timestamp));
  }

  /**
   * Simulates what MongoDB's {@code $set} would actually do: copies `previous`, then overwrites
   * only the keys the captured {@link Update} names, leaving everything else untouched. This is the
   * same mechanism the production {@link VehicleUpdateExecutor} relies on for atomicity, so
   * exercising it here (rather than stubbing a canned result) keeps these tests honest about what
   * the consumer actually asks Mongo to change.
   */
  private static Vehicle applyUpdate(Vehicle previous, Update update) {
    Vehicle copy = new Vehicle();
    copy.setId(previous.getId());
    copy.setName(previous.getName());
    copy.setStatus(previous.getStatus());
    copy.setPositionX(previous.getPositionX());
    copy.setPositionY(previous.getPositionY());
    copy.setBattery(previous.getBattery());
    copy.setCurrentOrderId(previous.getCurrentOrderId());
    copy.setLastSeenAt(previous.getLastSeenAt());
    copy.setOperationSince(previous.getOperationSince());

    Document set = update.getUpdateObject().get("$set", Document.class);
    if (set != null) {
      if (set.containsKey("positionX")) {
        copy.setPositionX(((Number) set.get("positionX")).doubleValue());
      }
      if (set.containsKey("positionY")) {
        copy.setPositionY(((Number) set.get("positionY")).doubleValue());
      }
      if (set.containsKey("battery")) {
        copy.setBattery(((Number) set.get("battery")).intValue());
      }
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

  private void stubExecutor(String vehicleId, Vehicle previous) {
    when(vehicleUpdateExecutor.apply(eq(vehicleId), any()))
        .thenAnswer(
            inv -> {
              Function<Vehicle, Update> builder = inv.getArgument(1);
              Update update = builder.apply(previous);
              Vehicle updated = applyUpdate(previous, update);
              return Optional.of(new VehicleUpdateExecutor.Result(previous.getStatus(), updated));
            });
  }

  @Test
  void consume_updatesVehicleSnapshotAndBroadcasts() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertEquals(14.2, captor.getValue().getPositionX(), 0.001);
    assertEquals(9.1, captor.getValue().getPositionY(), 0.001);
    assertEquals(79, captor.getValue().getBattery());
    assertEquals(VehicleStatus.BUSY, captor.getValue().getStatus());
  }

  @Test
  void consume_recordsBatteryTelemetry() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79));

    verify(telemetry).recordVehicleSample(new VehicleSample("vhc-1", 79));
  }

  @Test
  void consume_doesNotRecordTelemetryForAnUnknownVehicle() throws Exception {
    when(vehicleUpdateExecutor.apply(eq("ghost"), any())).thenReturn(Optional.empty());

    consumer.consume(message("ghost", 42));

    verify(telemetry, never()).recordVehicleSample(any());
  }

  @Test
  void consume_persistsAndBroadcastsEvenIfTelemetryBlowsUp() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    stubExecutor("vhc-1", vehicle);
    doThrow(new IllegalStateException("exporter exploded"))
        .when(telemetry)
        .recordVehicleSample(any());

    consumer.consume(message("vhc-1", 79));

    // Telemetry is recorded last precisely so a failure here cannot cost us the snapshot.
    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void recordsATransitionWhenTheStatusActuallyChanges() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.IDLE);
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79));

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("IDLE", captor.getValue().from());
    assertEquals("BUSY", captor.getValue().to());
    assertEquals(VehicleStatusChange.UNCATEGORIZED, captor.getValue().category());
  }

  @Test
  void recordsNoTransitionWhenTheStatusIsUnchanged() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.BUSY);
    stubExecutor("vhc-1", vehicle);

    // Rovers publish continuously. Counting every message would turn the transition counter into
    // a message counter and make every failure rate derived from it meaningless.
    consumer.consume(message("vhc-1", 79));

    verify(telemetry, never()).recordStatusTransition(any());
  }

  @Test
  void offlineToIdleTransitionStartsAFreshOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "idle", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertEquals(Instant.parse("2026-05-01T10:00:00Z"), captor.getValue().getOperationSince());
  }

  @Test
  void offlineToBusyTransitionStartsAFreshOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "busy", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertEquals(Instant.parse("2026-05-01T10:00:00Z"), captor.getValue().getOperationSince());
  }

  @Test
  void aTransitionBetweenTwoOnlineStatesDoesNotResetOperationSince() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.IDLE);
    Instant original = Instant.parse("2026-04-01T00:00:00Z");
    vehicle.setOperationSince(original);
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "busy", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertEquals(original, captor.getValue().getOperationSince());
  }

  @Test
  void aVehicleReportingOfflineDoesNotGetAnOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.OFFLINE);
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "offline", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertNull(captor.getValue().getOperationSince());
  }

  @Test
  void goingOfflineClearsAnExistingOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.BUSY);
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "offline", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertNull(
        captor.getValue().getOperationSince(),
        "a vehicle that just went offline has no ongoing operation window to report");
  }

  @Test
  void selfReportedErrorClearsAnExistingOperationWindow() throws Exception {
    Vehicle vehicle = new Vehicle();
    vehicle.setId("vhc-1");
    vehicle.setStatus(VehicleStatus.IDLE);
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    stubExecutor("vhc-1", vehicle);

    consumer.consume(message("vhc-1", 79, "error", "2026-05-01T10:00:00Z"));

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertNull(captor.getValue().getOperationSince());
  }

  @Test
  void consume_malformedPayload_isLoggedAndSwallowed() {
    consumer.consume("{oops");

    verify(vehicleUpdateExecutor, never()).apply(any(), any());
    verify(vehicleEventPublisher, never()).broadcastVehicleUpdate(any());
  }

  @Test
  void consume_unknownVehicle_isIgnored() throws Exception {
    when(vehicleUpdateExecutor.apply(eq("ghost"), any())).thenReturn(Optional.empty());

    String message =
        new ObjectMapper()
            .writeValueAsString(
                new VehicleTelemetryMessage(
                    "vehicle.telemetry",
                    "ghost",
                    new VehicleTelemetryMessage.Position(1.0, 2.0),
                    80,
                    "IDLE",
                    "2026-05-01T10:00:00Z"));

    consumer.consume(message);

    verify(vehicleEventPublisher, never()).broadcastVehicleUpdate(any());
  }
}
