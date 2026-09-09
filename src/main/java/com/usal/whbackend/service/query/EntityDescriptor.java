package com.usal.whbackend.service.query;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * One queryable entity.
 *
 * @param collectionName queried directly, so results can be assembled field by field from the raw
 *     document rather than by serializing a domain object. That way the response can only ever
 *     contain whitelisted fields, even if a projection were built wrongly.
 * @param defaultSort applied when the caller supplies no sort, so no query is ever unordered
 * @param unwindableArrays the only array fields a query may unwind. An unwind multiplies the
 *     document count by the array length, so which arrays are eligible is a deliberate choice
 *     rather than anything the caller may name.
 * @param requiresBoundedRange whether an aggregation over this entity must carry a date filter.
 *     True for collections that grow without limit, where an unbounded scan is the real risk. False
 *     for collections whose size is fixed by the warehouse — forcing a date window on those does
 *     not bound anything useful and silently drops every row created before it, which turns "stock
 *     on hand" into "stock on hand, of the pallets racked recently". Wrong quietly, which is worse
 *     than slow.
 */
public record EntityDescriptor(
    String name,
    String collectionName,
    Set<UserRole> requiredRoles,
    List<FieldDescriptor> fields,
    String defaultSort,
    Set<String> unwindableArrays,
    boolean requiresBoundedRange) {

  public EntityDescriptor {
    requiredRoles = Set.copyOf(requiredRoles);
    fields = List.copyOf(fields);
    unwindableArrays = Set.copyOf(unwindableArrays);
  }

  /** An entity with no unwindable arrays that must still be date-bounded to aggregate. */
  public EntityDescriptor(
      String name,
      String collectionName,
      Set<UserRole> requiredRoles,
      List<FieldDescriptor> fields,
      String defaultSort) {
    this(name, collectionName, requiredRoles, fields, defaultSort, Set.of(), true);
  }

  /** Accepts {@code created_at} or {@code createdAt} — callers should not have to guess. */
  public Optional<FieldDescriptor> field(String name) {
    String normalized = FieldNames.normalize(name);
    return fields.stream().filter(f -> f.name().equals(normalized)).findFirst();
  }

  public List<String> selectableFields() {
    return fields.stream().filter(FieldDescriptor::selectable).map(FieldDescriptor::name).toList();
  }

  public boolean canUnwind(String arrayName) {
    return unwindableArrays.contains(FieldNames.normalize(arrayName));
  }

  public boolean isVisibleTo(Set<UserRole> roles) {
    return roles.stream().anyMatch(requiredRoles::contains);
  }
}
