package com.usal.whbackend.service.query;

import com.usal.whbackend.api.query.EntityQueryRequest;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a validated request into a MongoDB aggregation pipeline.
 *
 * <p>Same discipline as {@link CriteriaTranslator}: every name is resolved through the entity's
 * whitelist before it can reach a stage, so an undeclared field, array or accumulator cannot be
 * expressed. The pipeline itself is built from {@link Document} literals rather than string
 * concatenation, so no caller-supplied text is ever parsed as an operator.
 *
 * <p>Stage order matters and is not arbitrary:
 *
 * <pre>
 * $match     filters on stored fields  — first, so the existing indexes are used
 * $unwind    the declared array, if requested
 * $match     filters on array members  — AFTER the unwind, so they select elements not documents
 * $addFields the derived fields actually referenced
 * $match     filters on derived fields — they do not exist before $addFields
 * $group
 * $project   flattens _id back to top level
 * $sort      on output aliases, which only exist after $project
 * $limit
 * </pre>
 */
@Component
public class AggregationTranslator {

  static final int MAX_GROUP_KEYS = 3;
  static final int MAX_AGGREGATES = 10;
  static final int MAX_GROUPED_ROWS = 1000;
  static final int DEFAULT_GROUPED_ROWS = 100;

  /** A quarter. Long enough for a semester's demand analysis, short enough to stay bounded. */
  static final Duration MAX_RANGE = Duration.ofDays(92);

  /**
   * The only limit MongoDB enforces for us. Every other rail here rejects a query before it runs;
   * this one stops a query that turned out to be expensive despite passing them.
   */
  static final Duration MAX_EXECUTION_TIME = Duration.ofSeconds(10);

  static final String DEFAULT_TIMEZONE = "America/Argentina/Buenos_Aires";

  private static final Pattern ALIAS = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

  private final CriteriaTranslator criteriaTranslator;

  public AggregationTranslator(CriteriaTranslator criteriaTranslator) {
    this.criteriaTranslator = criteriaTranslator;
  }

  /** One resolved group key: the output column and the expression producing it. */
  private record GroupKey(String alias, Object expression) {}

  /** One resolved aggregate: the output column and its accumulator document. */
  private record Aggregate(String alias, Document accumulator) {}

  public Aggregation translate(EntityQueryRequest request, EntityDescriptor entity) {
    if (request.groupBy().size() > MAX_GROUP_KEYS) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
    if (request.aggregates().size() > MAX_AGGREGATES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
    if (request.aggregates().isEmpty()) {
      // A grouped query with nothing to accumulate is a distinct-values list wearing a costume.
      // Rejecting it keeps every response shape predictable: group columns plus aggregate columns.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NO_AGGREGATES");
    }
    requireBoundedRange(request, entity);

    String timezone = validatedTimezone(request.timezone());
    List<GroupKey> keys = resolveGroupKeys(request, entity, timezone);
    List<Aggregate> aggregates = resolveAggregates(request, entity);
    rejectDuplicateAliases(keys, aggregates);

    List<AggregationOperation> stages = new ArrayList<>();
    appendMatches(stages, request, entity);
    appendGroup(stages, keys, aggregates);
    appendProjection(stages, keys, aggregates);
    appendSort(stages, request, keys, aggregates);
    stages.add(Aggregation.limit(normalizedRows(request.size())));

    return Aggregation.newAggregation(stages)
        .withOptions(
            AggregationOptions.builder()
                // Fail fast at MongoDB's 100MB in-memory limit rather than spilling to disk. On a
                // shared box, a query that thrashes the disk is worse than one that errors.
                .allowDiskUse(false)
                .maxTime(MAX_EXECUTION_TIME)
                .build());
  }

  private void appendMatches(
      List<AggregationOperation> stages, EntityQueryRequest request, EntityDescriptor entity) {

    List<EntityQueryRequest.Filter> stored = new ArrayList<>();
    List<EntityQueryRequest.Filter> arrayMembers = new ArrayList<>();
    List<EntityQueryRequest.Filter> derived = new ArrayList<>();

    for (EntityQueryRequest.Filter filter : request.filters()) {
      FieldDescriptor field =
          entity
              .field(filter.field())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
      if (field.isDerived()) {
        derived.add(filter);
      } else if (field.isArrayMember()) {
        arrayMembers.add(filter);
      } else {
        stored.add(filter);
      }
    }

    if (!arrayMembers.isEmpty() && request.unwind() == null) {
      // Filtering items.sku without unwinding selects orders that contain that SKU, then counts
      // every line on them. Silently returning the wrong total is worse than refusing.
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNWIND_REQUIRED");
    }

    criteriaTranslator.criteriaFor(stored, entity, false).forEach(c -> stages.add(match(c)));

    if (request.unwind() != null) {
      String array = FieldNames.normalize(request.unwind());
      if (!entity.canUnwind(array)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
      }
      stages.add(Aggregation.unwind(array));
    }

    criteriaTranslator.criteriaFor(arrayMembers, entity, false).forEach(c -> stages.add(match(c)));

    List<FieldDescriptor> referenced = referencedDerivedFields(request, entity);
    if (!referenced.isEmpty()) {
      Document addFields = new Document();
      for (FieldDescriptor field : referenced) {
        addFields.put(
            field.name(),
            new Document(
                "$subtract",
                List.of(
                    "$" + field.derivation().minuend(), "$" + field.derivation().subtrahend())));
      }
      stages.add(stage(new Document("$addFields", addFields)));
    }

    criteriaTranslator.criteriaFor(derived, entity, true).forEach(c -> stages.add(match(c)));
  }

  /**
   * Only the derived fields a request actually mentions get an {@code $addFields} entry. Computing
   * all of them would add a subtraction per document per unused field.
   */
  private List<FieldDescriptor> referencedDerivedFields(
      EntityQueryRequest request, EntityDescriptor entity) {
    Set<FieldDescriptor> referenced = new LinkedHashSet<>();
    for (EntityQueryRequest.Filter filter : request.filters()) {
      entity.field(filter.field()).filter(FieldDescriptor::isDerived).ifPresent(referenced::add);
    }
    for (EntityQueryRequest.AggregateSpec spec : request.aggregates()) {
      if (spec.field() != null) {
        entity.field(spec.field()).filter(FieldDescriptor::isDerived).ifPresent(referenced::add);
      }
    }
    return List.copyOf(referenced);
  }

  private List<GroupKey> resolveGroupKeys(
      EntityQueryRequest request, EntityDescriptor entity, String timezone) {
    List<GroupKey> keys = new ArrayList<>();
    for (EntityQueryRequest.GroupSpec spec : request.groupBy()) {
      FieldDescriptor field =
          entity
              .field(spec.field())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
      if (!field.groupable()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
      }
      if (field.isArrayMember() && request.unwind() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNWIND_REQUIRED");
      }

      String path = "$" + CriteriaTranslator.mongoField(field.name());
      Object expression = path;
      if (spec.bucket() != null && !spec.bucket().isBlank()) {
        if (field.type() != FieldType.INSTANT) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_BUCKET");
        }
        DateBucket bucket =
            DateBucket.parse(spec.bucket())
                .orElseThrow(
                    () ->
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_BUCKET"));
        expression =
            new Document(
                "$dateToString",
                new Document("format", bucket.format())
                    .append("date", path)
                    .append("timezone", timezone));
      }
      keys.add(new GroupKey(aliasFor(spec.as(), field.name()), expression));
    }
    return keys;
  }

  private List<Aggregate> resolveAggregates(EntityQueryRequest request, EntityDescriptor entity) {
    List<Aggregate> aggregates = new ArrayList<>();
    for (EntityQueryRequest.AggregateSpec spec : request.aggregates()) {
      AggregateOp op =
          AggregateOp.parse(spec.op())
              .orElseThrow(
                  () ->
                      new ResponseStatusException(
                          HttpStatus.BAD_REQUEST, "UNSUPPORTED_AGGREGATION"));

      if (!op.requiresField()) {
        aggregates.add(
            new Aggregate(
                aliasFor(spec.as(), op.name().toLowerCase(java.util.Locale.ROOT)),
                new Document("$sum", 1)));
        continue;
      }

      FieldDescriptor field =
          entity
              .field(spec.field())
              .orElseThrow(
                  () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD"));
      if (!field.aggregatable() || !op.accepts(field.type())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_AGGREGATION");
      }
      if (field.isArrayMember() && request.unwind() == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNWIND_REQUIRED");
      }

      String accumulator = "$" + op.name().toLowerCase(java.util.Locale.ROOT);
      String path = "$" + CriteriaTranslator.mongoField(field.name());
      aggregates.add(
          new Aggregate(aliasFor(spec.as(), field.name()), new Document(accumulator, path)));
    }
    return aggregates;
  }

  private void appendGroup(
      List<AggregationOperation> stages, List<GroupKey> keys, List<Aggregate> aggregates) {
    Document group = new Document();
    if (keys.isEmpty()) {
      // No group keys means one row for the whole match — "how many orders in total".
      group.put("_id", null);
    } else {
      Document id = new Document();
      keys.forEach(k -> id.put(k.alias(), k.expression()));
      group.put("_id", id);
    }
    aggregates.forEach(a -> group.put(a.alias(), a.accumulator()));
    stages.add(stage(new Document("$group", group)));
  }

  private void appendProjection(
      List<AggregationOperation> stages, List<GroupKey> keys, List<Aggregate> aggregates) {
    Document project = new Document("_id", 0);
    keys.forEach(k -> project.put(k.alias(), "$_id." + k.alias()));
    aggregates.forEach(a -> project.put(a.alias(), 1));
    stages.add(stage(new Document("$project", project)));
  }

  private void appendSort(
      List<AggregationOperation> stages,
      EntityQueryRequest request,
      List<GroupKey> keys,
      List<Aggregate> aggregates) {

    Set<String> aliases = new LinkedHashSet<>();
    keys.forEach(k -> aliases.add(k.alias()));
    aggregates.forEach(a -> aliases.add(a.alias()));

    if (request.sort().isEmpty()) {
      // Ascending on the first group key: for a date bucket that is chronological order, which is
      // what a chart wants. Callers ranking by value (top SKUs) say so explicitly.
      String first = keys.isEmpty() ? aggregates.get(0).alias() : keys.get(0).alias();
      stages.add(Aggregation.sort(Sort.by(Sort.Direction.ASC, first)));
      return;
    }

    List<Sort.Order> orders = new ArrayList<>();
    for (EntityQueryRequest.SortSpec spec : request.sort()) {
      String requested = spec.field() == null ? "" : spec.field().trim();
      if (!aliases.contains(requested)) {
        // In grouped mode the output columns are the aliases, not the source fields — sorting by
        // anything else would reference a column that no longer exists after $project.
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_FIELD");
      }
      orders.add(
          new Sort.Order(
              "asc".equalsIgnoreCase(spec.dir()) ? Sort.Direction.ASC : Sort.Direction.DESC,
              requested));
    }
    stages.add(Aggregation.sort(Sort.by(orders)));
  }

  /**
   * An aggregation must be anchored to a bounded window on a date field.
   *
   * <p>Without this a single request can scan every order ever created. The production box is a
   * shared machine, and an unbounded {@code $group} is the most plausible way to take it down.
   */
  private void requireBoundedRange(EntityQueryRequest request, EntityDescriptor entity) {
    Instant lower = null;
    Instant upper = null;

    for (EntityQueryRequest.Filter filter : request.filters()) {
      FieldDescriptor field = entity.field(filter.field()).orElse(null);
      if (field == null || field.type() != FieldType.INSTANT || field.isDerived()) {
        continue;
      }
      Operator op = Operator.parse(filter.op()).orElse(null);
      if (op == null) {
        continue;
      }
      Instant value = parseInstant(filter.value());
      if (value == null) {
        continue;
      }
      if ((op == Operator.GT || op == Operator.GTE) && (lower == null || value.isAfter(lower))) {
        lower = value;
      }
      if ((op == Operator.LT || op == Operator.LTE) && (upper == null || value.isBefore(upper))) {
        upper = value;
      }
    }

    if (lower == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNBOUNDED_RANGE");
    }
    Instant end = upper == null ? Instant.now() : upper;
    if (!end.isAfter(lower) || Duration.between(lower, end).compareTo(MAX_RANGE) > 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
    }
  }

  private Instant parseInstant(Object value) {
    if (value == null) {
      return null;
    }
    try {
      return Instant.parse(String.valueOf(value));
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private String validatedTimezone(String requested) {
    if (requested == null || requested.isBlank()) {
      return DEFAULT_TIMEZONE;
    }
    try {
      return ZoneId.of(requested.trim()).getId();
    } catch (DateTimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_TIMEZONE");
    }
  }

  /**
   * Output column names are validated, never interpolated raw: an alias reaches a {@code $project}
   * as a document key, where a leading {@code $} or an embedded dot would be read as an operator or
   * a path.
   */
  private String aliasFor(String requested, String fallbackField) {
    String alias =
        requested == null || requested.isBlank()
            ? FieldNames.toSnake(fallbackField).replace('.', '_')
            : requested.trim();
    if (!ALIAS.matcher(alias).matches()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS");
    }
    return alias;
  }

  private void rejectDuplicateAliases(List<GroupKey> keys, List<Aggregate> aggregates) {
    Set<String> seen = new LinkedHashSet<>();
    for (GroupKey key : keys) {
      if (!seen.add(key.alias())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS");
      }
    }
    for (Aggregate aggregate : aggregates) {
      // A group key and an aggregate sharing a name would silently overwrite one another in the
      // $project document.
      if (!seen.add(aggregate.alias())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_ALIAS");
      }
    }
  }

  private static AggregationOperation match(Criteria criteria) {
    return Aggregation.match(criteria);
  }

  private static AggregationOperation stage(Document document) {
    return context -> document;
  }

  public static int normalizedRows(Integer size) {
    if (size == null) {
      return DEFAULT_GROUPED_ROWS;
    }
    return Math.max(1, Math.min(size, MAX_GROUPED_ROWS));
  }
}
