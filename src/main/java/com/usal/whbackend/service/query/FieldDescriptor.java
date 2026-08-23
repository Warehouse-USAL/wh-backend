package com.usal.whbackend.service.query;

import java.util.List;
import java.util.Set;

/**
 * One queryable field.
 *
 * <p>{@code filterable} is a separate flag from {@code selectable} on purpose. Blocking projection
 * alone is not enough for a secret: a field that can be filtered on is a blind oracle, revealing
 * its value one comparison at a time. Anything sensitive must be false on all three.
 */
public record FieldDescriptor(
    String name,
    FieldType type,
    boolean filterable,
    boolean sortable,
    boolean selectable,
    Set<Operator> permittedOperators) {

  public FieldDescriptor {
    permittedOperators = Set.copyOf(permittedOperators);
  }

  /** A normal, fully queryable field. */
  public static FieldDescriptor of(String name, FieldType type) {
    return new FieldDescriptor(name, type, true, true, true, defaultOperators(type));
  }

  /** Readable and sortable, but not filterable. */
  public static FieldDescriptor readOnly(String name, FieldType type) {
    return new FieldDescriptor(name, type, false, true, true, Set.of());
  }

  /** Completely inaccessible: cannot be read, sorted or filtered. Used for secrets. */
  public static FieldDescriptor hidden(String name, FieldType type) {
    return new FieldDescriptor(name, type, false, false, false, Set.of());
  }

  private static Set<Operator> defaultOperators(FieldType type) {
    return switch (type) {
      case STRING ->
          Set.of(
              Operator.EQ,
              Operator.NE,
              Operator.IN,
              Operator.NIN,
              Operator.CONTAINS,
              Operator.EXISTS);
      case ENUM -> Set.of(Operator.EQ, Operator.NE, Operator.IN, Operator.NIN, Operator.EXISTS);
      case BOOLEAN -> Set.of(Operator.EQ, Operator.NE, Operator.EXISTS);
      case NUMBER, INSTANT ->
          Set.of(
              Operator.EQ,
              Operator.NE,
              Operator.GT,
              Operator.GTE,
              Operator.LT,
              Operator.LTE,
              Operator.IN,
              Operator.NIN,
              Operator.EXISTS);
    };
  }

  public boolean permits(Operator operator) {
    return permittedOperators.contains(operator);
  }

  public static List<FieldDescriptor> copy(List<FieldDescriptor> fields) {
    return List.copyOf(fields);
  }
}
