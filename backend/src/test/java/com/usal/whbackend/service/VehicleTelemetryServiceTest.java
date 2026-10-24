package com.usal.whbackend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleIds;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.kafka.VehicleUpdateExecutor;
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
class VehicleTelemetryServiceTest {

  private static final String TS = "2026-05-01T10:00:00Z";
  private static final String INFRA_ID = "vhc-1";
  private static final String ID = VehicleIds.fromInfra(INFRA_ID);

  @Mock VehicleUpdateExecutor vehicleUpdateExecutor;
  @Mock VehicleEventPublisher vehicleEventPublisher;
  @Mock TelemetryPort telemetry;
  VehicleTelemetryService service;

  @BeforeEach
  void setUp() {
    service = new VehicleTelemetryService(vehicleUpdateExecutor, vehicleEventPublisher, telemetry);
  }

  /**
   * Simulates what MongoDB's {@code $set} would do: copies {@code previous}, then overwrites only
   * the keys the captured {@link Update} names. Same mechanism the production {@link
   * VehicleUpdateExecutor} relies on, so these tests stay honest about what the service asks Mongo
   * to change.
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

  private static Vehicle vehicle(VehicleStatus status) {
    Vehicle v = new Vehicle();
    v.setId(ID);
    v.setStatus(status);
    return v;
  }

  private Vehicle appliedUpdate(Vehicle previous) {
    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    return captor.getValue();
  }

  @Test
  void apply_updatesStatusBatteryAndLastSeenAndBroadcasts() {
    Vehicle vehicle = vehicle(VehicleStatus.IDLE);
    stubExecutor(ID, vehicle);

    assertEquals(ID, service.apply("vhc-1", "busy", 79, null, TS));

    Vehicle updated = appliedUpdate(vehicle);
    assertEquals(VehicleStatus.BUSY, updated.getStatus());
    assertEquals(79, updated.getBattery());
    assertEquals(Instant.parse(TS), updated.getLastSeenAt());
  }

  @Test
  void apply_neverTouchesThePosition() {
    Vehicle vehicle = vehicle(VehicleStatus.IDLE);
    vehicle.setPositionX(3.5);
    vehicle.setPositionY(7.5);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "busy", 79, null, TS);

    Vehicle updated = appliedUpdate(vehicle);
    assertEquals(3.5, updated.getPositionX(), 0.001);
    assertEquals(7.5, updated.getPositionY(), 0.001);
  }

  @Test
  void apply_isCaseInsensitiveAboutStatus() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "IDLE", 50, null, TS);

    assertEquals(VehicleStatus.IDLE, appliedUpdate(vehicle).getStatus());
  }

  @Test
  void apply_nullBatteryKeepsTheLastKnownOne() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    vehicle.setBattery(64);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "busy", null, null, TS);

    assertEquals(64, appliedUpdate(vehicle).getBattery());
  }

  @Test
  void apply_unrecognizedStatusStillAppliesBatteryAndLastSeen() {
    Vehicle vehicle = vehicle(VehicleStatus.IDLE);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "hovering", 41, null, TS);

    Vehicle updated = appliedUpdate(vehicle);
    assertEquals(VehicleStatus.IDLE, updated.getStatus());
    assertEquals(41, updated.getBattery());
    verify(telemetry, never()).recordStatusTransition(any());
  }

  @Test
  void apply_malformedTimestampFallsBackToNow() {
    Vehicle vehicle = vehicle(VehicleStatus.IDLE);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "idle", 90, null, "yesterday-ish");

    assertTrue(appliedUpdate(vehicle).getLastSeenAt().isAfter(Instant.parse(TS)));
  }

  @Test
  void apply_createsTheVehicleFromTheInfraIdBeforeApplyingTheReading() {
    stubExecutor(ID, vehicle(VehicleStatus.OFFLINE));

    service.apply(" vhc-1 ", "idle", 80, null, TS);

    verify(vehicleUpdateExecutor).ensureExists(ID, "vhc-1");
  }

  @Test
  void apply_recordsTheBatterySample() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "busy", 79, null, TS);

    verify(telemetry).recordVehicleSample(new VehicleSample(ID, 79));
  }

  @Test
  void apply_persistsAndBroadcastsEvenIfTelemetryBlowsUp() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    stubExecutor(ID, vehicle);
    doThrow(new IllegalStateException("exporter exploded"))
        .when(telemetry)
        .recordVehicleSample(any());

    assertEquals(ID, service.apply("vhc-1", "busy", 79, null, TS));

    verify(vehicleEventPublisher).broadcastVehicleUpdate(any(Vehicle.class));
  }

  @Test
  void apply_recordsATransitionWhenTheStatusActuallyChanges() {
    stubExecutor(ID, vehicle(VehicleStatus.IDLE));

    service.apply("vhc-1", "busy", 79, null, TS);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("IDLE", captor.getValue().from());
    assertEquals("BUSY", captor.getValue().to());
    assertEquals(VehicleStatusChange.UNCATEGORIZED, captor.getValue().category());
  }

  @Test
  void apply_recordsNoTransitionWhenTheStatusIsUnchanged() {
    stubExecutor(ID, vehicle(VehicleStatus.BUSY));

    // Vehicles report continuously. Counting every reading would turn the transition counter into
    // a message counter and make every failure rate derived from it meaningless.
    service.apply("vhc-1", "busy", 79, null, TS);

    verify(telemetry, never()).recordStatusTransition(any());
  }

  @Test
  void apply_vehicleWithNoPriorStatusRecordsTransitionFromUnknown() {
    stubExecutor(ID, vehicle(null));

    service.apply("vhc-1", "idle", 80, null, TS);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("UNKNOWN", captor.getValue().from());
  }

  @Test
  void apply_offlineToIdleStartsAFreshOperationWindow() {
    Vehicle vehicle = vehicle(VehicleStatus.OFFLINE);
    vehicle.setOperationSince(null);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "idle", 80, null, TS);

    assertEquals(Instant.parse(TS), appliedUpdate(vehicle).getOperationSince());
  }

  @Test
  void apply_errorToBusyStartsAFreshOperationWindow() {
    stubExecutor(ID, vehicle(VehicleStatus.ERROR));

    service.apply("vhc-1", "busy", 80, null, TS);

    ArgumentCaptor<Vehicle> captor = ArgumentCaptor.forClass(Vehicle.class);
    verify(vehicleEventPublisher).broadcastVehicleUpdate(captor.capture());
    assertEquals(Instant.parse(TS), captor.getValue().getOperationSince());
  }

  @Test
  void apply_betweenTwoOnlineStatesDoesNotResetOperationSince() {
    Vehicle vehicle = vehicle(VehicleStatus.IDLE);
    Instant since = Instant.parse("2026-04-01T00:00:00Z");
    vehicle.setOperationSince(since);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "busy", 80, null, TS);

    assertEquals(since, appliedUpdate(vehicle).getOperationSince());
  }

  @Test
  void apply_goingOfflineClearsAnExistingOperationWindow() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", "offline", 80, null, TS);

    assertNull(appliedUpdate(vehicle).getOperationSince());
  }

  @Test
  void apply_withAFault_broadcastsTheErrorAndRecordsItsCodeAsTheTransitionCategory() {
    stubExecutor(ID, vehicle(VehicleStatus.BUSY));

    service.apply(
        "vhc-1",
        "error",
        74,
        new VehicleTelemetryService.Fault("CONNECTION_LOST", "Sin conexión con rover"),
        TS);

    verify(vehicleEventPublisher)
        .broadcastVehicleError(ID, "CONNECTION_LOST", "Sin conexión con rover", TS);
    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("BUSY", captor.getValue().from());
    assertEquals("ERROR", captor.getValue().to());
    assertEquals("CONNECTION_LOST", captor.getValue().category());
  }

  @Test
  void apply_withAFaultAndErrorStatus_clearsTheOperationWindow() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    vehicle.setOperationSince(Instant.parse("2026-04-01T00:00:00Z"));
    stubExecutor(ID, vehicle);

    service.apply(
        "vhc-1", "error", 74, new VehicleTelemetryService.Fault("CONNECTION_LOST", "x"), TS);

    assertNull(appliedUpdate(vehicle).getOperationSince());
  }

  @Test
  void apply_withAFaultButNoStatus_treatsTheVehicleAsInError() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    stubExecutor(ID, vehicle);

    service.apply("vhc-1", null, 74, new VehicleTelemetryService.Fault("COLLISION", "x"), TS);

    assertEquals(VehicleStatus.ERROR, appliedUpdate(vehicle).getStatus());
  }

  @Test
  void apply_withAFaultWhileStillBusy_doesNotChangeTheStatus() {
    Vehicle vehicle = vehicle(VehicleStatus.BUSY);
    stubExecutor(ID, vehicle);

    // Infra reports STOP_NOT_DETECTED while the vehicle is still driving home.
    service.apply(
        "vhc-1",
        "busy",
        77,
        new VehicleTelemetryService.Fault("STOP_NOT_DETECTED", "STOP_NOT_DETECTED: WH2|PA2"),
        TS);

    assertEquals(VehicleStatus.BUSY, appliedUpdate(vehicle).getStatus());
    verify(vehicleEventPublisher)
        .broadcastVehicleError(eq(ID), eq("STOP_NOT_DETECTED"), any(), any());
    verify(telemetry, never()).recordStatusTransition(any());
  }

  @Test
  void apply_withAnUnrecognizedFaultCode_fallsBackToOther() {
    stubExecutor(ID, vehicle(VehicleStatus.BUSY));

    service.apply(
        "vhc-1", "error", 74, new VehicleTelemetryService.Fault("SOMETHING_NEW", "x"), TS);

    ArgumentCaptor<VehicleStatusChange> captor = ArgumentCaptor.forClass(VehicleStatusChange.class);
    verify(telemetry).recordStatusTransition(captor.capture());
    assertEquals("OTHER", captor.getValue().category());
  }

  @Test
  void apply_repeatedFaultWithTheSameStatus_recordsNoSecondTransition() {
    stubExecutor(ID, vehicle(VehicleStatus.ERROR));

    service.apply(
        "vhc-1", "error", 74, new VehicleTelemetryService.Fault("CONNECTION_LOST", "x"), TS);

    verify(telemetry, never()).recordStatusTransition(any());
  }
}
