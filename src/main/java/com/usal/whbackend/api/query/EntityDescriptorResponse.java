package com.usal.whbackend.api.query;

import com.usal.whbackend.service.query.EntityDescriptor;
import com.usal.whbackend.service.query.FieldDescriptor;
import com.usal.whbackend.service.query.FieldNames;
import com.usal.whbackend.service.query.Operator;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * A catalogue entry: everything a client needs to build a valid query, and nothing more.
 *
 * <p>Fields that are neither readable nor filterable are omitted entirely rather than listed as
 * restricted — confirming that {@code passwordHash} exists serves no caller.
 */
public record EntityDescriptorResponse(String name, List<Field> fields) {

  public EntityDescriptorResponse {
    fields = List.copyOf(fields);
  }

  public record Field(
      String name,
      String type,
      boolean filterable,
      boolean sortable,
      boolean selectable,
      List<String> operators) {
    public Field {
      operators = List.copyOf(operators);
    }
  }

  public static EntityDescriptorResponse from(EntityDescriptor entity) {
    List<Field> fields =
        entity.fields().stream()
            .filter(f -> f.selectable() || f.filterable() || f.sortable())
            .map(EntityDescriptorResponse::toField)
            .toList();
    return new EntityDescriptorResponse(entity.name(), fields);
  }

  private static Field toField(FieldDescriptor field) {
    return new Field(
        FieldNames.toSnake(field.name()),
        field.type().name().toLowerCase(Locale.ROOT),
        field.filterable(),
        field.sortable(),
        field.selectable(),
        field.permittedOperators().stream()
            .map(Operator::name)
            .map(o -> o.toLowerCase(Locale.ROOT))
            .sorted(Comparator.naturalOrder())
            .toList());
  }
}
