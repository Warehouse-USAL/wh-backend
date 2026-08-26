package com.usal.whbackend.service.query;

import com.mongodb.MongoExecutionTimeoutException;
import com.usal.whbackend.api.query.EntityQueryRequest;
import com.usal.whbackend.domain.UserRole;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Runs whitelisted entity queries.
 *
 * <p>Results are read as raw {@link Document}s and reassembled field by field from the entity's
 * selectable list, rather than by serializing a domain object. That is deliberate: it means the
 * response can only ever contain whitelisted fields, so a mistake in the projection cannot leak a
 * hidden one such as {@code passwordHash}.
 */
@Service
public class EntityQueryService {

  private final EntityRegistry registry;
  private final CriteriaTranslator translator;
  private final AggregationTranslator aggregationTranslator;
  private final MongoTemplate mongoTemplate;

  public EntityQueryService(
      EntityRegistry registry,
      CriteriaTranslator translator,
      AggregationTranslator aggregationTranslator,
      MongoTemplate mongoTemplate) {
    this.registry = registry;
    this.translator = translator;
    this.aggregationTranslator = aggregationTranslator;
    this.mongoTemplate = mongoTemplate;
  }

  public Page<Map<String, Object>> query(
      String entityName, EntityQueryRequest request, Set<UserRole> roles) {

    EntityDescriptor entity =
        registry
            .findByName(entityName)
            .filter(e -> e.isVisibleTo(roles))
            // Unknown rather than forbidden, so the catalogue cannot be probed for entities that
            // exist but are out of reach for this caller.
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_ENTITY"));

    if (request.isAggregation()) {
      return aggregate(entity, request);
    }

    Query query = translator.translate(request, entity);

    int page = CriteriaTranslator.normalizedPage(request.page());
    int size = CriteriaTranslator.normalizedSize(request.size());

    long total = mongoTemplate.count(Query.of(query).limit(0).skip(0), entity.collectionName());
    List<Document> documents =
        mongoTemplate.find(
            query.skip((long) page * size).limit(size), Document.class, entity.collectionName());

    List<String> projected =
        request.fields().isEmpty()
            ? entity.selectableFields()
            : request.fields().stream()
                .map(f -> entity.field(f).map(FieldDescriptor::name).orElseThrow())
                .toList();

    List<Map<String, Object>> items = documents.stream().map(d -> toItem(d, projected)).toList();
    return new PageImpl<>(items, PageRequest.of(page, size), total);
  }

  /**
   * Grouped mode. Rows come back already shaped by the pipeline's {@code $project}, and every key
   * in them is an alias this service validated, so unlike the document path there is nothing to
   * re-whitelist — a field that was never named cannot appear.
   */
  private Page<Map<String, Object>> aggregate(EntityDescriptor entity, EntityQueryRequest request) {
    Aggregation aggregation = aggregationTranslator.translate(request, entity);

    List<Document> rows;
    try {
      rows =
          mongoTemplate
              .aggregate(aggregation, entity.collectionName(), Document.class)
              .getMappedResults();
    } catch (DataAccessException e) {
      if (exceededALimit(e)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "QUERY_TOO_BROAD");
      }
      throw e;
    }

    List<Map<String, Object>> items = rows.stream().map(this::toRow).toList();
    // The row count IS the total: a grouped query is not paged, it is capped. Reporting the cap
    // as a page count would imply a page two that does not exist.
    return new PageImpl<>(
        items,
        PageRequest.of(0, AggregationTranslator.normalizedRows(request.size())),
        items.size());
  }

  /**
   * Distinguishes "this query was too expensive" from "the database is broken".
   *
   * <p>maxTime and the 100MB in-memory ceiling both surface as generic data-access failures; only
   * the former two are the caller's fault, and only they should read as a 400.
   */
  private boolean exceededALimit(DataAccessException e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof MongoExecutionTimeoutException) {
        return true;
      }
      String message = cause.getMessage();
      if (message != null && message.contains("exceeded memory limit")) {
        return true;
      }
    }
    return false;
  }

  private Map<String, Object> toRow(Document row) {
    Map<String, Object> item = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : row.entrySet()) {
      Object value = entry.getValue();
      if (value instanceof Date date) {
        value = date.toInstant();
      }
      if (value instanceof ObjectId objectId) {
        value = objectId.toHexString();
      }
      item.put(entry.getKey(), value);
    }
    return item;
  }

  private Map<String, Object> toItem(Document document, List<String> fields) {
    Map<String, Object> item = new LinkedHashMap<>();
    for (String field : fields) {
      Object value = document.get(CriteriaTranslator.mongoField(field));
      // BSON dates come back as java.util.Date; the rest of the API speaks ISO-8601 instants.
      if (value instanceof Date date) {
        value = date.toInstant();
      }
      // Collections whose @Id is not a String hand back an ObjectId, which would otherwise
      // serialize as a {date, timestamp} object rather than the id string every other endpoint
      // returns. Reading raw Documents means Spring Data's usual conversion does not apply.
      if (value instanceof ObjectId objectId) {
        value = objectId.toHexString();
      }
      item.put(FieldNames.toSnake(field), value);
    }
    return item;
  }

  public List<EntityDescriptor> catalog(Set<UserRole> roles) {
    return registry.visibleTo(roles);
  }
}
