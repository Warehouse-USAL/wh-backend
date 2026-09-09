package com.usal.whbackend.service.query;

/**
 * Translates between the domain's camelCase field names and the snake_case the API speaks.
 *
 * <p>Needed because Jackson's naming strategy does not apply to Map keys, and query results are
 * assembled as maps. Without this the query endpoints would answer in camelCase while every other
 * endpoint answers in snake_case.
 */
public final class FieldNames {

  private FieldNames() {}

  public static String toSnake(String camel) {
    StringBuilder out = new StringBuilder(camel.length() + 4);
    for (int i = 0; i < camel.length(); i++) {
      char c = camel.charAt(i);
      if (Character.isUpperCase(c)) {
        if (i > 0) {
          out.append('_');
        }
        out.append(Character.toLowerCase(c));
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  public static String toCamel(String snake) {
    if (snake.indexOf('_') < 0) {
      return snake;
    }
    StringBuilder out = new StringBuilder(snake.length());
    boolean upperNext = false;
    for (char c : snake.toCharArray()) {
      if (c == '_') {
        upperNext = true;
      } else {
        out.append(upperNext ? Character.toUpperCase(c) : c);
        upperNext = false;
      }
    }
    return out.toString();
  }

  /** Accepts either spelling from a caller and returns the internal camelCase name. */
  public static String normalize(String requested) {
    return requested == null ? "" : toCamel(requested.trim());
  }
}
