package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.api.query.EntityQueryRequest;
import com.usal.whbackend.api.query.EntityQueryRequest.Filter;
import com.usal.whbackend.api.query.EntityQueryRequest.SortSpec;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.stream.IntStream;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

class CriteriaTranslatorTest {

  private final CriteriaTranslator translator = new CriteriaTranslator();
  private final EntityRegistry registry = new EntityRegistry();

  private final EntityDescriptor orders = registry.findByName("orders").orElseThrow();
  private final EntityDescriptor users = registry.findByName("users").orElseThrow();

  private static EntityQueryRequest request(List<Filter> filters) {
    return new EntityQueryRequest(filters, List.of(), List.of(), 0, 25, null, null, null, null);
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  /**
   * The regression this whole class exists to prevent. Two criteria for one field on a single Query
   * throws InvalidMongoDbApiUsageException; a date range is the ordinary case that hits it.
   */
  @Test
  void twoFiltersOnTheSameFieldAreChainedRatherThanAddedTwice() {
    EntityQueryRequest request =
        request(
            List.of(
                new Filter("createdAt", "gte", "2026-08-01T00:00:00Z"),
                new Filter("createdAt", "lte", "2026-08-31T00:00:00Z")));

    assertThatCode(() -> translator.translate(request, orders)).doesNotThrowAnyException();

    Document criteria = translator.translate(request, orders).getQueryObject();
    Document createdAt = criteria.get("createdAt", Document.class);
    assertThat(createdAt.get("$gte")).isEqualTo(Date.from(Instant.parse("2026-08-01T00:00:00Z")));
    assertThat(createdAt.get("$lte")).isEqualTo(Date.from(Instant.parse("2026-08-31T00:00:00Z")));
  }

  /** The same field spelled two ways in one request must still collapse to a single criteria. */
  @Test
  void mixedSpellingsOfOneFieldStillCollapseToASingleCriteria() {
    EntityQueryRequest request =
        request(
            List.of(
                new Filter("created_at", "gte", "2026-08-01T00:00:00Z"),
                new Filter("createdAt", "lte", "2026-08-31T00:00:00Z")));

    assertThatCode(() -> translator.translate(request, orders)).doesNotThrowAnyException();

    Document createdAt =
        translator.translate(request, orders).getQueryObject().get("createdAt", Document.class);
    assertThat(createdAt.get("$gte")).isEqualTo(Date.from(Instant.parse("2026-08-01T00:00:00Z")));
    assertThat(createdAt.get("$lte")).isEqualTo(Date.from(Instant.parse("2026-08-31T00:00:00Z")));
  }

  @Test
  void coercesInstantStringsToDatesSoBsonComparisonActuallyMatches() {
    Query query =
        translator.translate(
            request(List.of(new Filter("createdAt", "gte", "2026-08-01T00:00:00Z"))), orders);

    Object bound = query.getQueryObject().get("createdAt", Document.class).get("$gte");
    // A raw String — or an unconverted Instant — would silently match nothing rather than error.
    assertThat(bound).isInstanceOf(Date.class);
    assertThat(bound).isEqualTo(Date.from(Instant.parse("2026-08-01T00:00:00Z")));
  }

  @Test
  void mapsIdToUnderscoreId() {
    Query query = translator.translate(request(List.of(new Filter("id", "eq", "ORD-1"))), orders);
    assertThat(query.getQueryObject().get("_id")).isEqualTo("ORD-1");
  }

  @Test
  void acceptsSnakeCaseFieldNamesFromCallers() {
    Query query =
        translator.translate(
            request(List.of(new Filter("assigned_vehicle_id", "eq", "VHC-1"))), orders);
    assertThat(query.getQueryObject().get("assignedVehicleId")).isEqualTo("VHC-1");
  }

  @Test
  void appliesInOperatorAsAList() {
    Query query =
        translator.translate(
            request(List.of(new Filter("status", "in", List.of("COMPLETED", "CANCELLED")))),
            orders);
    assertThat(query.getQueryObject().get("status", Document.class).get("$in"))
        .isEqualTo(List.of("COMPLETED", "CANCELLED"));
  }

  @Test
  void containsIsBuiltFromAnEscapedLiteralNotACallerSuppliedPattern() {
    Query query =
        translator.translate(
            request(List.of(new Filter("destinationArea", "contains", ".*"))), orders);

    Object regex = query.getQueryObject().get("destinationArea");
    // Pattern.quote wraps in \Q...\E so the metacharacters are matched literally.
    assertThat(regex.toString()).contains("\\Q.*\\E");
  }

  @Test
  void rejectsUnknownField() {
    assertThatThrownBy(
            () -> translator.translate(request(List.of(new Filter("nope", "eq", "x"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void refusesToFilterOnAHiddenFieldSoItCannotBeUsedAsAnOracle() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("passwordHash", "eq", "$2a$10$abc"))), users))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void refusesToProjectAHiddenField() {
    EntityQueryRequest request =
        new EntityQueryRequest(
            List.of(), List.of(), List.of("passwordHash"), 0, 25, null, null, null, null);
    assertThatThrownBy(() -> translator.translate(request, users))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void refusesToSortByAHiddenField() {
    EntityQueryRequest request =
        new EntityQueryRequest(
            List.of(),
            List.of(new SortSpec("passwordHash", "asc")),
            List.of(),
            0,
            25,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> translator.translate(request, users))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void rejectsAnOperatorTheFieldTypeDoesNotPermit() {
    // status is an ENUM: ordering comparisons are meaningless on it.
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("status", "gt", "PENDING"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_OPERATOR"));
  }

  @Test
  void rejectsAnOperatorThatDoesNotExist() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("status", "$where", "1==1"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_OPERATOR"));
  }

  @Test
  void rejectsAnUnparseableInstant() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("createdAt", "gte", "last tuesday"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));
  }

  @Test
  void rejectsInWithoutAList() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("status", "in", "COMPLETED"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));
  }

  @Test
  void rejectsTooManyFilters() {
    List<Filter> many =
        IntStream.range(0, CriteriaTranslator.MAX_FILTERS + 1)
            .mapToObj(i -> new Filter("status", "eq", "PENDING"))
            .toList();
    assertThatThrownBy(() -> translator.translate(request(many), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("TOO_MANY_FILTERS"));
  }

  @Test
  void rejectsAnOverlongFilterValue() {
    String huge = "x".repeat(CriteriaTranslator.MAX_VALUE_LENGTH + 1);
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("destinationArea", "eq", huge))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));
  }

  @Test
  void appliesADefaultSortSoPagingIsStable() {
    Query query = translator.translate(request(List.of()), orders);
    assertThat(query.getSortObject().get("createdAt")).isEqualTo(-1);
  }

  @Test
  void projectsOnlySelectableFieldsWhenNoneRequested() {
    Query query = translator.translate(request(List.of()), users);
    Document fields = query.getFieldsObject();
    assertThat(fields.keySet()).contains("email", "name", "role");
    assertThat(fields.keySet()).doesNotContain("passwordHash");
  }

  @Test
  void clampsPageSizeToTheCeiling() {
    assertThat(CriteriaTranslator.normalizedSize(10_000)).isEqualTo(CriteriaTranslator.MAX_SIZE);
    assertThat(CriteriaTranslator.normalizedSize(null)).isEqualTo(CriteriaTranslator.DEFAULT_SIZE);
    assertThat(CriteriaTranslator.normalizedSize(0)).isEqualTo(1);
    assertThat(CriteriaTranslator.normalizedPage(-5)).isZero();
  }

  @Test
  void derivedFieldsDoNotExistOutsideAnAggregation() {
    // There is no $addFields stage in a find, so cycleTimeMs is simply not on the stored
    // document. Accepting the filter would match nothing and return an empty page with no error.
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("cycle_time_ms", "lt", 1000))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void arrayMembersAreNotProjectableEvenThoughTheyAreFilterable() {
    EntityQueryRequest request =
        new EntityQueryRequest(
            List.of(), List.of(), List.of("items.sku"), 0, 25, null, null, null, null);
    assertThatThrownBy(() -> translator.translate(request, orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  private final EntityDescriptor products = registry.findByName("products").orElseThrow();

  @Test
  void rendersEveryPermittedOperator() {
    // One assertion per operator, because each maps to a different Criteria method and a wrong
    // mapping produces a query that runs and quietly returns the wrong rows.
    Query q =
        translator.translate(
            request(
                List.of(
                    new Filter("status", "ne", "CANCELLED"),
                    new Filter("created_at", "gt", "2026-01-01T00:00:00Z"),
                    new Filter("destination_area", "nin", List.of("Z1", "Z2")),
                    new Filter("cancel_reason", "exists", false))),
            orders);

    String rendered = q.getQueryObject().toJson();
    assertThat(rendered).contains("$ne").contains("$gt").contains("$nin").contains("$exists");
  }

  @Test
  void coercesNumbersAndBooleans() {
    Query numeric =
        translator.translate(request(List.of(new Filter("minimum_stock", "gte", 5))), products);
    assertThat(numeric.getQueryObject().toJson()).contains("minimumStock");

    Query numericFromText =
        translator.translate(request(List.of(new Filter("minimum_stock", "gte", "5"))), products);
    assertThat(numericFromText.getQueryObject().toJson()).contains("minimumStock");

    Query bool = translator.translate(request(List.of(new Filter("active", "eq", true))), products);
    assertThat(bool.getQueryObject().toJson()).contains("active");

    Query boolFromText =
        translator.translate(request(List.of(new Filter("active", "eq", "TRUE"))), products);
    assertThat(boolFromText.getQueryObject().toJson()).contains("active");
  }

  @Test
  void rejectsValuesThatCannotBeCoerced() {
    assertThatThrownBy(
            () -> translator.translate(request(List.of(new Filter("status", "eq", null))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("minimum_stock", "eq", "abc"))), products))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("active", "eq", "perhaps"))), products))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("created_at", "gte", "last tuesday"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));
  }

  @Test
  void inAndNinNeedAnActualList() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("status", "in", "COMPLETED"))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(List.of(new Filter("status", "in", List.of()))), orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));
  }

  @Test
  void honoursAnExplicitSortDirectionAndRejectsAnUnknownSortField() {
    EntityQueryRequest ascending =
        new EntityQueryRequest(
            List.of(),
            List.of(new SortSpec("created_at", "asc")),
            List.of(),
            0,
            25,
            null,
            null,
            null,
            null);
    assertThat(translator.translate(ascending, orders).getSortObject().toJson())
        .contains("createdAt")
        .contains("1");

    EntityQueryRequest unknown =
        new EntityQueryRequest(
            List.of(),
            List.of(new SortSpec("nope", "desc")),
            List.of(),
            0,
            25,
            null,
            null,
            null,
            null);
    assertThatThrownBy(() -> translator.translate(unknown, orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void normalisesPagingSoNoRequestCanAskForAnUnboundedPage() {
    assertThat(CriteriaTranslator.normalizedPage(null)).isZero();
    assertThat(CriteriaTranslator.normalizedPage(-4)).isZero();
    assertThat(CriteriaTranslator.normalizedPage(7)).isEqualTo(7);
    assertThat(CriteriaTranslator.normalizedSize(null)).isEqualTo(CriteriaTranslator.DEFAULT_SIZE);
    assertThat(CriteriaTranslator.normalizedSize(10_000)).isEqualTo(CriteriaTranslator.MAX_SIZE);
    assertThat(CriteriaTranslator.normalizedSize(0)).isEqualTo(1);
  }
}
