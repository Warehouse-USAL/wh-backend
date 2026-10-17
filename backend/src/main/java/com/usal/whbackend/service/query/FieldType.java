package com.usal.whbackend.service.query;

/**
 * Declared type of a queryable field.
 *
 * <p>Drives coercion of filter values. This matters more than it looks: MongoDB stores an {@code
 * Instant} as a BSON date, so comparing it against the raw JSON string would match nothing and
 * return an empty page with no error — a silent wrong answer.
 */
public enum FieldType {
  STRING,
  NUMBER,
  BOOLEAN,
  INSTANT,
  ENUM
}
