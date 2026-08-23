package com.usal.whbackend.service.query;

import com.usal.whbackend.api.query.EntityQueryRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a validated request into a MongoDB {@link Query}.
 *
 * <p>Field names are never taken from the request: each is resolved through the entity's {@link
 * FieldDescriptor} list first, so an unknown name is rejected rather than interpolated. The
 * operator set is an allow-list, so {@code $where}, {@code $function} and {@code $expr} cannot be
 * expressed at all.
 */
@Component
public class CriteriaTranslator {

  static final int MAX_FILTERS = 10;
  static final int MAX_SIZE = 100;
  static final int DEFAULT_SIZE = 25;
  static final int MAX_VALUE_LENGTH = 200;

  public Query translate(EntityQueryRequest request, EntityDescriptor entity) {
    if (request.filters().size() > MAX_FILTERS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "TOO_MANY_FILTERS");
    }

    Query query = new Query();
    buildCriteria(request, entity).forEach(query::addCriteria);
    query.with(sortOf(request, entity));
    applyProjection(request, entity, query);
    return query;
  }

  /**
   * One {@link Criteria} per distinct field, with every operator on that field chained onto it.
   *
   * <p>This grouping is required, not stylistic: adding two criteria for the same field to one
   * Query throws {@code InvalidMongoDbApiUsageException}. A range filter — {@code createdAt >= a}
   * plus {@code createdAt <= b} — is the common case that hits it. The same trap is documented in
   * {@code OrderRepository.findByFilters}.
   */
  private List<Criteria> buildCriteria(EntityQueryRequest request, EntityDescriptor entity) {
    // Grouped by the RESOLVED field, not the name as written. A caller may spell the same field
    // `created_at` in one filter and `createdAt` in another; grouping on the raw text would make
    // those two groups, producing exactly the duplicate-criteria crash this method prevents.
    Map<FieldDescriptor, List<EntityQueryRequest.Filter>> byField = new LinkedHashMap<>();
    for (EntityQueryRequest.Filter filter : request.filters()) {
      FieldDescriptor field = filterableField(entity, filter.field());
      byField.computeIfAbsent(field, k -> new ArrayList<>()).add(filter);
    }

    List<Criteria> result = new ArrayList<>();
    byField.forEach(
        (field, filters) -> {
          Criteria criteria = Criteria.where(mongoField(field.name()));
          for (EntityQueryRequest.Filter filter : filters) {
            apply(criteria, field, filter);
          }
          result.add(criteria);
        });
    return result;
  }

  private FieldDescriptor filterableField(EntityDescriptor entity, String name) {
    FieldDescriptor field =
        entity
            .field(name)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
    if (!field.filterable()) {
      // Reported as UNKNOWN_FIELD, not "forbidden": confirming a hidden field exists is itself
      // a leak, and for a password hash the existence of a filter oracle is the whole risk.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
    }
    return field;
  }

  private void apply(Criteria criteria, FieldDescriptor field, EntityQueryRequest.Filter filter) {
    Operator operator =
        Operator.parse(filter.op())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OPERATOR"));
    if (!field.permits(operator)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OPERATOR");
    }

    switch (operator) {
      case EQ -> criteria.is(coerce(field, filter.value()));
      case NE -> criteria.ne(coerce(field, filter.value()));
      case GT -> criteria.gt(coerce(field, filter.value()));
      case GTE -> criteria.gte(coerce(field, filter.value()));
      case LT -> criteria.lt(coerce(field, filter.value()));
      case LTE -> criteria.lte(coerce(field, filter.value()));
      case IN -> criteria.in(coerceAll(field, filter.value()));
      case NIN -> criteria.nin(coerceAll(field, filter.value()));
      case EXISTS -> criteria.exists(asBoolean(filter.value()));
      // Built from an escaped literal — the caller supplies text to look for, never a pattern.
      case CONTAINS ->
          criteria.regex(
              Pattern.compile(
                  Pattern.quote(asBoundedString(filter.value())), Pattern.CASE_INSENSITIVE));
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_OPERATOR");
    }
  }

  private Object coerce(FieldDescriptor field, Object value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
    }
    try {
      return switch (field.type()) {
        // Stored as a BSON date. Converted here rather than relying on Spring Data's
        // execution-time mapping: results are read as raw Documents with no entity metadata, so
        // there is nothing to tell the mapper this field is temporal. An unconverted Instant
        // would match nothing and return an empty page with no error.
        case INSTANT -> Date.from(Instant.parse(String.valueOf(value)));
        case NUMBER -> value instanceof Number n ? n : Double.valueOf(String.valueOf(value));
        case BOOLEAN -> asBoolean(value);
        case STRING, ENUM -> asBoundedString(value);
      };
    } catch (DateTimeParseException | NumberFormatException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
    }
  }

  private List<Object> coerceAll(FieldDescriptor field, Object value) {
    if (!(value instanceof Collection<?> values)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
    }
    if (values.isEmpty() || values.size() > MAX_FILTERS * 10) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
    }
    return values.stream().map(v -> coerce(field, v)).toList();
  }

  private boolean asBoolean(Object value) {
    if (value instanceof Boolean b) {
      return b;
    }
    String text = String.valueOf(value);
    if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
      return Boolean.parseBoolean(text);
    }
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
  }

  private String asBoundedString(Object value) {
    String text = String.valueOf(value);
    if (text.length() > MAX_VALUE_LENGTH) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_FILTER_VALUE");
    }
    return text;
  }

  private Sort sortOf(EntityQueryRequest request, EntityDescriptor entity) {
    if (request.sort().isEmpty()) {
      // Never leave a query unordered: without this, paging over an unsorted collection can
      // repeat or skip documents between pages.
      return Sort.by(Sort.Direction.DESC, mongoField(entity.defaultSort()));
    }
    List<Sort.Order> orders = new ArrayList<>();
    for (EntityQueryRequest.SortSpec spec : request.sort()) {
      FieldDescriptor field =
          entity
              .field(spec.field())
              .filter(FieldDescriptor::sortable)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
      Sort.Direction direction =
          "asc".equalsIgnoreCase(spec.dir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
      orders.add(new Sort.Order(direction, mongoField(field.name())));
    }
    return Sort.by(orders);
  }

  private void applyProjection(EntityQueryRequest request, EntityDescriptor entity, Query query) {
    List<String> requested =
        request.fields().isEmpty() ? entity.selectableFields() : request.fields();
    for (String name : requested) {
      FieldDescriptor field =
          entity
              .field(name)
              .filter(FieldDescriptor::selectable)
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
      query.fields().include(mongoField(field.name()));
    }
  }

  /** The domain calls it {@code id}; MongoDB stores it as {@code _id}. */
  static String mongoField(String name) {
    return "id".equals(name) ? "_id" : name;
  }

  public static int normalizedSize(Integer size) {
    if (size == null) {
      return DEFAULT_SIZE;
    }
    return Math.max(1, Math.min(size, MAX_SIZE));
  }

  public static int normalizedPage(Integer page) {
    return page == null ? 0 : Math.max(0, page);
  }
}
