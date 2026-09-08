package com.usal.whbackend.repository.kafka;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Applies a targeted, atomic field-level update to a {@link Vehicle} document.
 *
 * <p>{@link VehicleTelemetryConsumer} and {@link VehicleErrorConsumer} both mutate the same vehicle
 * document from independently-threaded {@code @KafkaListener} containers on two different topics. A
 * whole-document read -&gt; mutate in memory -&gt; {@code save()} lets one consumer's save silently
 * revert fields the other consumer changed concurrently (a lost update): if consumer A saves the
 * full entity right after consumer B read its own (now stale) copy, B's later save overwrites A's
 * change with the stale value B never knew had moved.
 *
 * <p>Restricting each consumer to a {@code $set} of only the fields it actually owns, applied via a
 * single {@code findAndModify}, removes that failure mode structurally rather than by coordination:
 * MongoDB applies the update atomically, so a concurrent write from the sibling consumer can only
 * land wholly before or wholly after it, and can only ever clobber a field this update also names —
 * never a field it left untouched.
 */
@Component
class VehicleUpdateExecutor {

  private final MongoTemplate mongoTemplate;

  VehicleUpdateExecutor(MongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  /**
   * @param previousStatus the vehicle's status immediately before this update was applied
   * @param updated the vehicle document immediately after this update was applied
   */
  record Result(VehicleStatus previousStatus, Vehicle updated) {}

  /**
   * Reads the current state of vehicle {@code vehicleId}, hands it to {@code updateBuilder} to
   * decide what to change (the builder may inspect the previous state, e.g. "only start a fresh
   * operation window if the vehicle was previously OFFLINE"), then atomically applies exactly that
   * {@code Update} — never a whole-document save. Empty if no such vehicle exists.
   *
   * <p>The read used to decide the update and the atomic write are two separate operations, so
   * under extreme concurrent interleaving the decision could rarely be based on a status that has
   * already moved on by the time the write lands — this is a real, accepted trade-off, not a false
   * guarantee. What is guaranteed unconditionally is that the write itself can never clobber a
   * field it does not name, which is the actual data-loss failure mode this class exists to
   * prevent.
   */
  Optional<Result> apply(String vehicleId, Function<Vehicle, Update> updateBuilder) {
    Vehicle previous = mongoTemplate.findById(vehicleId, Vehicle.class);
    if (previous == null) {
      return Optional.empty();
    }
    Update update = updateBuilder.apply(previous);
    Query query = Query.query(Criteria.where("_id").is(vehicleId));
    Vehicle updated =
        mongoTemplate.findAndModify(
            query, update, FindAndModifyOptions.options().returnNew(true), Vehicle.class);
    if (updated == null) {
      // Vehicle was deleted between the two reads above; treat like "not found".
      return Optional.empty();
    }
    return Optional.of(new Result(previous.getStatus(), updated));
  }
}
