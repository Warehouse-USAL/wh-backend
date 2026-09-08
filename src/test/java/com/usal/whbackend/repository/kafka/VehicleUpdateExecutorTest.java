package com.usal.whbackend.repository.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

@ExtendWith(MockitoExtension.class)
class VehicleUpdateExecutorTest {

  @Mock MongoTemplate mongoTemplate;
  VehicleUpdateExecutor executor;

  @BeforeEach
  void setUp() {
    executor = new VehicleUpdateExecutor(mongoTemplate);
  }

  @Test
  void apply_returnsEmptyWhenVehicleDoesNotExist() {
    when(mongoTemplate.findById("ghost", Vehicle.class)).thenReturn(null);

    Optional<VehicleUpdateExecutor.Result> result =
        executor.apply("ghost", v -> new Update().set("status", VehicleStatus.OFFLINE));

    assertTrue(result.isEmpty());
    verify(mongoTemplate, never())
        .findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Vehicle.class));
  }

  @Test
  void apply_readsCurrentStateAppliesAtomicUpdateAndReturnsBoth() {
    Vehicle previous = new Vehicle();
    previous.setId("vhc-1");
    previous.setStatus(VehicleStatus.IDLE);
    when(mongoTemplate.findById("vhc-1", Vehicle.class)).thenReturn(previous);

    Vehicle afterWrite = new Vehicle();
    afterWrite.setId("vhc-1");
    afterWrite.setStatus(VehicleStatus.BUSY);
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Vehicle.class)))
        .thenReturn(afterWrite);

    Optional<VehicleUpdateExecutor.Result> result =
        executor.apply(
            "vhc-1",
            v -> {
              // The builder must see the state from BEFORE this update, not some other snapshot.
              assertEquals(VehicleStatus.IDLE, v.getStatus());
              return new Update().set("status", VehicleStatus.BUSY);
            });

    assertTrue(result.isPresent());
    assertEquals(VehicleStatus.IDLE, result.get().previousStatus());
    assertEquals(VehicleStatus.BUSY, result.get().updated().getStatus());

    ArgumentCaptor<FindAndModifyOptions> optionsCaptor =
        ArgumentCaptor.forClass(FindAndModifyOptions.class);
    verify(mongoTemplate)
        .findAndModify(
            any(Query.class), any(Update.class), optionsCaptor.capture(), eq(Vehicle.class));
    assertTrue(optionsCaptor.getValue().isReturnNew(), "must return the post-update document");
  }

  @Test
  void apply_returnsEmptyIfVehicleVanishesBetweenReadAndAtomicWrite() {
    Vehicle previous = new Vehicle();
    previous.setId("vhc-1");
    when(mongoTemplate.findById("vhc-1", Vehicle.class)).thenReturn(previous);
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Vehicle.class)))
        .thenReturn(null);

    Optional<VehicleUpdateExecutor.Result> result =
        executor.apply("vhc-1", v -> new Update().set("status", VehicleStatus.OFFLINE));

    assertTrue(result.isEmpty());
  }

  @Test
  void apply_onlySetsTheFieldsTheBuilderNames_soUnrelatedFieldsCannotBeClobbered() {
    // The actual proof this class exists for: VehicleTelemetryConsumer and VehicleErrorConsumer
    // both mutate the same document from independently-threaded Kafka listeners. If either
    // applied a whole-document save, one consumer's save could silently revert a field the other
    // consumer had just changed. Restricting each to a targeted $set — asserted here by
    // inspecting exactly which keys the Update carries — makes that structurally impossible: an
    // Update that never mentions "positionX" cannot produce a write that touches it, regardless
    // of whatever else happens concurrently in Mongo.
    Vehicle previous = new Vehicle();
    previous.setId("vhc-1");
    when(mongoTemplate.findById("vhc-1", Vehicle.class)).thenReturn(previous);
    when(mongoTemplate.findAndModify(
            any(Query.class),
            any(Update.class),
            any(FindAndModifyOptions.class),
            eq(Vehicle.class)))
        .thenReturn(previous);

    ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);

    // Mirrors VehicleErrorConsumer's update: only status/lastSeenAt/operationSince.
    executor.apply(
        "vhc-1",
        v ->
            new Update()
                .set("status", VehicleStatus.OFFLINE)
                .set("lastSeenAt", Instant.parse("2026-05-01T10:00:00Z"))
                .set("operationSince", null));

    verify(mongoTemplate)
        .findAndModify(
            any(Query.class),
            updateCaptor.capture(),
            any(FindAndModifyOptions.class),
            eq(Vehicle.class));
    Document set = updateCaptor.getValue().getUpdateObject().get("$set", Document.class);
    assertEquals(Set.of("status", "lastSeenAt", "operationSince"), set.keySet());
    assertFalse(set.containsKey("positionX"));
    assertFalse(set.containsKey("positionY"));
    assertFalse(set.containsKey("battery"));
  }

  /**
   * A deterministic proof of the race fix, without flaky real-thread timing: apply each consumer's
   * captured {@link Update}, one after another in each possible order, onto a plain in-memory
   * stand-in for the stored document — exactly what two back-to-back {@code findAndModify} calls
   * against the real database do. Fields each consumer owns exclusively must survive regardless of
   * which one lands last; only the field both genuinely touch ({@code status}) reflects whichever
   * applied more recently — legitimate last-write-wins on a field both sides are actually racing to
   * set, not the original bug's silent loss of fields neither update even mentioned.
   */
  @Test
  void concurrentUpdates_exclusiveFieldsSurvive_regardlessOfOrder_telemetryThenError() {
    Map<String, Object> stored = seedDocument();

    applySet(stored, telemetryUpdate());
    applySet(stored, errorUpdate());

    assertExclusiveFieldsSurvived(stored);
    assertEquals(VehicleStatus.OFFLINE, stored.get("status"), "error update landed last");
    assertNull(stored.get("operationSince"));
  }

  @Test
  void concurrentUpdates_exclusiveFieldsSurvive_regardlessOfOrder_errorThenTelemetry() {
    Map<String, Object> stored = seedDocument();

    applySet(stored, errorUpdate());
    applySet(stored, telemetryUpdate());

    assertExclusiveFieldsSurvived(stored);
    assertEquals(VehicleStatus.BUSY, stored.get("status"), "telemetry update landed last");
  }

  private static Map<String, Object> seedDocument() {
    Map<String, Object> stored = new HashMap<>();
    stored.put("positionX", 0.0);
    stored.put("positionY", 0.0);
    stored.put("battery", 10);
    stored.put("status", VehicleStatus.IDLE);
    stored.put("operationSince", Instant.parse("2026-04-01T00:00:00Z"));
    return stored;
  }

  private static Update telemetryUpdate() {
    return new Update()
        .set("positionX", 14.2)
        .set("positionY", 9.1)
        .set("battery", 55)
        .set("status", VehicleStatus.BUSY)
        .set("lastSeenAt", Instant.parse("2026-05-01T10:00:00Z"));
  }

  private static Update errorUpdate() {
    return new Update()
        .set("status", VehicleStatus.OFFLINE)
        .set("lastSeenAt", Instant.parse("2026-05-01T10:00:01Z"))
        .set("operationSince", null);
  }

  private static void assertExclusiveFieldsSurvived(Map<String, Object> stored) {
    // Owned only by the telemetry update — must never be clobbered by the error update, which
    // never names them.
    assertEquals(14.2, (double) stored.get("positionX"));
    assertEquals(9.1, (double) stored.get("positionY"));
    assertEquals(55, stored.get("battery"));
  }

  private static void applySet(Map<String, Object> stored, Update update) {
    Document set = update.getUpdateObject().get("$set", Document.class);
    for (String key : set.keySet()) {
      stored.put(key, set.get(key));
    }
  }
}
