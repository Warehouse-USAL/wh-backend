package com.usal.whbackend.api.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A whitelisted query over one entity. Serialized snake_case on the wire. */
public record EntityQueryRequest(
    List<Filter> filters, List<SortSpec> sort, List<String> fields, Integer page, Integer size) {

  public record Filter(String field, String op, Object value) {}

  public record SortSpec(String field, String dir) {}

  /**
   * Copies defensively and normalises absent collections to empty.
   *
   * <p>Not {@code List.copyOf}: a body containing a null element would otherwise become a 500
   * instead of a validation failure.
   */
  public EntityQueryRequest {
    filters = filters == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(filters));
    sort = sort == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(sort));
    fields = fields == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(fields));
  }
}
