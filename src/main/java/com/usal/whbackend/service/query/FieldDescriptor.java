package com.usal.whbackend.service.query;

import java.util.List;
import java.util.Set;

/**
 * One queryable field.
 *
 * <p>{@code filterable} is a separate flag from {@code selectable} on purpose. Blocking projection
 * alone is not enough for a secret: a field that can be filtered on is a blind oracle, revealing
 * its value one comparison at a time. Anything sensitive must be false on all of them.
 *
 * @param derivation non-null only for fields computed at query time; see {@link #derived}
 */
public record FieldDescriptor(
    String name,
    FieldType type,
    boolean filterable,
    boolean sortable,
    boolean selectable,
    boolean groupable,
    boolean aggregatable,
    Set<Operator> permittedOperators,
    Derivation derivation) {

  /**
   * A field computed from two stored ones by subtraction.
   *
   * <p>Deliberately narrow: subtraction of two fields, nothing else. The alternative — letting
   * callers write arithmetic — would mean exposing an expression parser, and would let every
   * consumer invent its own definition of the same quantity.
   */
  public record Derivation(String minuend, String subtrahend) {}

  public FieldDescriptor {
    permittedOperators = Set.copyOf(permittedOperators);
  }

  /** A normal, fully queryable stored field. */
  public static FieldDescriptor of(String name, FieldType type) {
    return new FieldDescriptor(
        name, type, true, true, true, true, isAggregatable(type), defaultOperators(type), null);
  }

  /** Readable and sortable, but not filterable. */
  public static FieldDescriptor readOnly(String name, FieldType type) {
    return new FieldDescriptor(
        name, type, false, true, true, true, isAggregatable(type), Set.of(), null);
  }

  /** Completely inaccessible: cannot be read, sorted, filtered, grouped or aggregated. */
  public static FieldDescriptor hidden(String name, FieldType type) {
    return new FieldDescriptor(name, type, false, false, false, false, false, Set.of(), null);
  }

  /**
   * A field inside a declared array, named with a dot: {@code items.sku}.
   *
   * <p>Not selectable or sortable, because outside an aggregation it addresses a whole array rather
   * than one value. Filterable and groupable, which is what makes "units sold per SKU" expressible.
   */
  public static FieldDescriptor inArray(String name, FieldType type) {
    return new FieldDescriptor(
        name, type, true, false, false, true, isAggregatable(type), defaultOperators(type), null);
  }

  /**
   * A field computed during aggregation, such as {@code completedAt - createdAt}.
   *
   * <p>Filterable as well as aggregatable, and that is the point: filtering is how a caller applies
   * its own threshold — "orders whose cycle time was under four hours" — without the backend ever
   * knowing what the threshold means. Not groupable: the values are near-unique, so grouping on one
   * would produce a row per document.
   */
  public static FieldDescriptor derived(
      String name, FieldType type, String minuend, String subtrahend) {
    return new FieldDescriptor(
        name,
        type,
        true,
        false,
        false,
        false,
        true,
        defaultOperators(type),
        new Derivation(minuend, subtrahend));
  }

  private static boolean isAggregatable(FieldType type) {
    return type == FieldType.NUMBER || type == FieldType.INSTANT;
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

  /** True for fields that exist only inside an aggregation pipeline. */
  public boolean isDerived() {
    return derivation != null;
  }

  /** True for fields addressed through a declared array, e.g. {@code items.sku}. */
  public boolean isArrayMember() {
    return name.indexOf('.') >= 0;
  }

  public static List<FieldDescriptor> copy(List<FieldDescriptor> fields) {
    return List.copyOf(fields);
  }
}
