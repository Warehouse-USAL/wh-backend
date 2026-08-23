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
 */
public record EntityDescriptor(
    String name,
    String collectionName,
    Set<UserRole> requiredRoles,
    List<FieldDescriptor> fields,
    String defaultSort) {

  public EntityDescriptor {
    requiredRoles = Set.copyOf(requiredRoles);
    fields = List.copyOf(fields);
  }

  /** Accepts {@code created_at} or {@code createdAt} — callers should not have to guess. */
  public Optional<FieldDescriptor> field(String name) {
    String normalized = FieldNames.normalize(name);
    return fields.stream().filter(f -> f.name().equals(normalized)).findFirst();
  }

  public List<String> selectableFields() {
    return fields.stream().filter(FieldDescriptor::selectable).map(FieldDescriptor::name).toList();
  }

  public boolean isVisibleTo(Set<UserRole> roles) {
    return roles.stream().anyMatch(requiredRoles::contains);
  }
}
