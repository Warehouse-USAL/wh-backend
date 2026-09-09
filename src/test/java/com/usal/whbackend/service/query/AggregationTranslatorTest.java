package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.api.query.EntityQueryRequest;
import com.usal.whbackend.api.query.EntityQueryRequest.AggregateSpec;
import com.usal.whbackend.api.query.EntityQueryRequest.Filter;
import com.usal.whbackend.api.query.EntityQueryRequest.GroupSpec;
import com.usal.whbackend.api.query.EntityQueryRequest.SortSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.web.server.ResponseStatusException;

class AggregationTranslatorTest {

  private final EntityRegistry registry = new EntityRegistry();
  private final AggregationTranslator translator =
      new AggregationTranslator(new CriteriaTranslator());

  private final EntityDescriptor orders = registry.findByName("orders").orElseThrow();
  private final EntityDescriptor users = registry.findByName("users").orElseThrow();

  private static final String FROM = "2026-08-01T00:00:00Z";
  private static final String TO = "2026-09-01T00:00:00Z";

  /** Every aggregation must be date-bounded, so most tests need this window. */
  private static List<Filter> window() {
    return new ArrayList<>(
        List.of(new Filter("created_at", "gte", FROM), new Filter("created_at", "lt", TO)));
  }

  private static EntityQueryRequest request(
      List<Filter> filters, String unwind, List<GroupSpec> groups, List<AggregateSpec> aggs) {
    return new EntityQueryRequest(
        filters, List.of(), List.of(), null, null, unwind, groups, aggs, null);
  }

  private List<Document> pipelineOf(EntityQueryRequest request, EntityDescriptor entity) {
    return translator.translate(request, entity).toPipeline(Aggregation.DEFAULT_CONTEXT);
  }

  private static List<String> stageNames(List<Document> pipeline) {
    return pipeline.stream().map(d -> d.keySet().iterator().next()).toList();
  }

  private static Document stage(List<Document> pipeline, String name) {
    return pipeline.stream()
        .filter(d -> d.containsKey(name))
        .findFirst()
        .map(d -> d.get(name, Document.class))
        .orElseThrow(() -> new AssertionError("no " + name + " stage in " + stageNames(pipeline)));
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  @Test
  void buildsTheTopSkusPipeline() {
    List<Document> pipeline =
        pipelineOf(
            request(
                window(),
                "items",
                List.of(new GroupSpec("items.sku", null, "sku")),
                List.of(new AggregateSpec("sum", "items.quantity", "units"))),
            orders);

    assertThat(stageNames(pipeline))
        .containsExactly("$match", "$unwind", "$group", "$project", "$sort", "$limit");

    Document group = stage(pipeline, "$group");
    assertThat(group.get("_id", Document.class).get("sku")).isEqualTo("$items.sku");
    assertThat(group.get("units", Document.class).get("$sum")).isEqualTo("$items.quantity");

    // _id is flattened away so a grouped row has the same flat shape as a document row.
    Document project = stage(pipeline, "$project");
    assertThat(project.get("_id")).isEqualTo(0);
    assertThat(project.get("sku")).isEqualTo("$_id.sku");
  }

  @Test
  void countNeedsNoFieldAndBecomesSumOfOne() {
    Document group =
        stage(
            pipelineOf(
                request(
                    window(),
                    null,
                    List.of(new GroupSpec("status", null, "status")),
                    List.of(new AggregateSpec("count", null, "orders"))),
                orders),
            "$group");

    assertThat(group.get("orders", Document.class).get("$sum")).isEqualTo(1);
  }

  @Test
  void bucketsDatesWithDateToStringBecauseMongoFourFourHasNoDateTrunc() {
    Document group =
        stage(
            pipelineOf(
                request(
                    window(),
                    null,
                    List.of(new GroupSpec("created_at", "day", "day")),
                    List.of(new AggregateSpec("count", null, "orders"))),
                orders),
            "$group");

    Document expression = (Document) group.get("_id", Document.class).get("day");
    Document dateToString = expression.get("$dateToString", Document.class);
    assertThat(dateToString.get("format")).isEqualTo("%Y-%m-%d");
    assertThat(dateToString.get("date")).isEqualTo("$createdAt");
    // A UTC day boundary would push three hours of every warehouse evening into the next day.
    assertThat(dateToString.get("timezone")).isEqualTo("America/Argentina/Buenos_Aires");
  }

  @Test
  void honoursAnExplicitTimezoneAndRejectsAnUnknownOne() {
    EntityQueryRequest utc =
        new EntityQueryRequest(
            window(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("created_at", "hour", "hour")),
            List.of(new AggregateSpec("count", null, "orders")),
            "UTC");
    Document dateToString =
        ((Document) stage(pipelineOf(utc, orders), "$group").get("_id", Document.class).get("hour"))
            .get("$dateToString", Document.class);
    assertThat(dateToString.get("timezone")).isEqualTo("UTC");

    EntityQueryRequest nonsense =
        new EntityQueryRequest(
            window(),
            List.of(),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("created_at", "hour", "hour")),
            List.of(new AggregateSpec("count", null, "orders")),
            "Mars/Olympus_Mons");
    assertThatThrownBy(() -> translator.translate(nonsense, orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_TIMEZONE"));
  }

  @Test
  void filtersOnArrayMembersAreAppliedAfterTheUnwindNotBefore() {
    List<Filter> filters = window();
    filters.add(new Filter("items.sku", "eq", "SKU-1"));

    List<Document> pipeline =
        pipelineOf(
            request(
                filters,
                "items",
                List.of(new GroupSpec("items.sku", null, "sku")),
                List.of(new AggregateSpec("sum", "items.quantity", "units"))),
            orders);

    // Matching items.sku before the unwind would select orders CONTAINING that SKU and then sum
    // every line on them — a silently wrong total rather than an error.
    assertThat(stageNames(pipeline))
        .containsExactly("$match", "$unwind", "$match", "$group", "$project", "$sort", "$limit");
    assertThat(stageNames(pipeline).indexOf("$unwind"))
        .isLessThan(stageNames(pipeline).lastIndexOf("$match"));
  }

  @Test
  void derivedFieldsAreComputedOnlyWhenReferencedAndFilteredAfterTheyExist() {
    List<Document> untouched =
        pipelineOf(
            request(
                window(),
                null,
                List.of(new GroupSpec("status", null, "status")),
                List.of(new AggregateSpec("count", null, "orders"))),
            orders);
    assertThat(stageNames(untouched)).doesNotContain("$addFields");

    List<Filter> filters = window();
    filters.add(new Filter("cycle_time_ms", "lt", 14400000));

    List<Document> pipeline =
        pipelineOf(
            request(
                filters,
                null,
                List.of(new GroupSpec("status", null, "status")),
                List.of(new AggregateSpec("avg", "cycle_time_ms", "avg_cycle"))),
            orders);

    assertThat(stageNames(pipeline))
        .containsExactly("$match", "$addFields", "$match", "$group", "$project", "$sort", "$limit");

    Document added = stage(pipeline, "$addFields").get("cycleTimeMs", Document.class);
    assertThat(added.getList("$subtract", String.class))
        .containsExactly("$completedAt", "$createdAt");
  }

  @Test
  void anUnboundedAggregationIsRefused() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        List.of(),
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNBOUNDED_RANGE"));
  }

  @Test
  void aRangeWiderThanTheCapIsRefused() {
    List<Filter> tooWide =
        List.of(
            new Filter("created_at", "gte", "2024-01-01T00:00:00Z"),
            new Filter("created_at", "lt", "2026-01-01T00:00:00Z"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        tooWide,
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void anOpenEndedRangeIsBoundedAgainstNow() {
    List<Filter> recent =
        List.of(new Filter("created_at", "gte", Instant.now().minusSeconds(3600).toString()));
    assertThatCode(
            () ->
                translator.translate(
                    request(
                        recent,
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .doesNotThrowAnyException();

    List<Filter> ancient = List.of(new Filter("created_at", "gte", "2020-01-01T00:00:00Z"));
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        ancient,
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void arrayMembersCannotBeReachedWithoutAnUnwind() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("items.sku", null, "sku")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNWIND_REQUIRED"));

    List<Filter> filters = window();
    filters.add(new Filter("items.sku", "eq", "SKU-1"));
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        filters,
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNWIND_REQUIRED"));
  }

  @Test
  void onlyDeclaredArraysMayBeUnwound() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        "address",
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("count", null, "orders"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void sumAndAvgAreRefusedOnFieldsThatAreNotNumeric() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of(new AggregateSpec("sum", "created_at", "nonsense"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_AGGREGATION"));
  }

  @Test
  void maxIsAllowedOnAnInstantBecauseLastOrderedIsARealQuestion() {
    Document group =
        stage(
            pipelineOf(
                request(
                    window(),
                    "items",
                    List.of(new GroupSpec("items.sku", null, "sku")),
                    List.of(new AggregateSpec("max", "created_at", "last_ordered"))),
                orders),
            "$group");

    assertThat(group.get("last_ordered", Document.class).get("$max")).isEqualTo("$createdAt");
  }

  @Test
  void aHiddenFieldCannotBeGroupedOrAggregated() {
    List<Filter> filters =
        new ArrayList<>(List.of(new Filter("created_at", "gte", Instant.now().toString())));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        filters,
                        null,
                        List.of(new GroupSpec("password_hash", null, "leak")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    users))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void aliasesAreValidatedBecauseTheyBecomeDocumentKeys() {
    // A blank alias is not hostile — it means "use the default", so it is not in this list.
    for (String hostile : List.of("$where", "a.b", "Units", "1units", "items.sku")) {
      assertThatThrownBy(
              () ->
                  translator.translate(
                      request(
                          window(),
                          null,
                          List.of(new GroupSpec("status", null, hostile)),
                          List.of(new AggregateSpec("count", null, "orders"))),
                      orders))
          .describedAs("alias %s", hostile)
          .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_ALIAS"));
    }
  }

  @Test
  void aBlankAliasFallsBackToTheFieldNameInSnakeCase() {
    Document project =
        stage(
            pipelineOf(
                request(
                    window(),
                    "items",
                    List.of(new GroupSpec("items.productId", null, null)),
                    List.of(new AggregateSpec("sum", "items.quantity", null))),
                orders),
            "$project");

    // Dots cannot survive into a key, so they become underscores.
    assertThat(project).containsKeys("items_product_id", "items_quantity");
  }

  @Test
  void anAliasCollisionBetweenAGroupKeyAndAnAggregateIsRefused() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "total")),
                        List.of(new AggregateSpec("count", null, "total"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_ALIAS"));
  }

  @Test
  void groupingWithNothingToAccumulateIsRefused() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "status")),
                        List.of()),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("NO_AGGREGATES"));
  }

  @Test
  void tooManyGroupKeysAreRefused() {
    List<GroupSpec> keys =
        List.of(
            new GroupSpec("status", null, "a"),
            new GroupSpec("destination_area", null, "b"),
            new GroupSpec("assigned_vehicle_id", null, "c"),
            new GroupSpec("requested_by_user_id", null, "d"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(window(), null, keys, List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void sortMustNameAnOutputColumnNotASourceField() {
    EntityQueryRequest bySourceField =
        new EntityQueryRequest(
            window(),
            List.of(new SortSpec("created_at", "desc")),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("status", null, "status")),
            List.of(new AggregateSpec("count", null, "orders")),
            null);

    // After $project the only columns that exist are the aliases.
    assertThatThrownBy(() -> translator.translate(bySourceField, orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));

    EntityQueryRequest byAlias =
        new EntityQueryRequest(
            window(),
            List.of(new SortSpec("orders", "desc")),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("status", null, "status")),
            List.of(new AggregateSpec("count", null, "orders")),
            null);
    assertThatCode(() -> translator.translate(byAlias, orders)).doesNotThrowAnyException();
  }

  @Test
  void defaultsToChronologicalOrderOnTheFirstGroupKey() {
    List<Document> pipeline =
        pipelineOf(
            request(
                window(),
                null,
                List.of(new GroupSpec("created_at", "day", "day")),
                List.of(new AggregateSpec("count", null, "orders"))),
            orders);

    assertThat(stage(pipeline, "$sort").get("day")).isEqualTo(1);
  }

  @Test
  void rowCountIsCappedAndDefaulted() {
    assertThat(AggregationTranslator.normalizedRows(null))
        .isEqualTo(AggregationTranslator.DEFAULT_GROUPED_ROWS);
    assertThat(AggregationTranslator.normalizedRows(50_000))
        .isEqualTo(AggregationTranslator.MAX_GROUPED_ROWS);
    assertThat(AggregationTranslator.normalizedRows(-3)).isEqualTo(1);
  }

  @Test
  void stockOnHandNeedsNoDateWindowBecauseOneWouldDropOlderPallets() {
    EntityDescriptor positions = registry.findByName("positions").orElseThrow();

    // The regression this guards: requiring a date filter here silently excludes every position
    // racked before the window, understating stock with no error. Correctness beats a bounded
    // scan on a collection whose size is fixed by the warehouse.
    assertThatCode(
            () ->
                translator.translate(
                    request(
                        List.of(),
                        null,
                        List.of(new GroupSpec("product_id", null, "product_id")),
                        List.of(new AggregateSpec("sum", "current_stock", "on_hand"))),
                    positions))
        .doesNotThrowAnyException();
  }

  @Test
  void ordersStillRequireADateWindowBecauseTheyGrowWithoutLimit() {
    assertThat(orders.requiresBoundedRange()).isTrue();
    assertThat(registry.findByName("positions").orElseThrow().requiresBoundedRange()).isFalse();
    assertThat(registry.findByName("products").orElseThrow().requiresBoundedRange()).isFalse();
    assertThat(registry.findByName("vehicles").orElseThrow().requiresBoundedRange()).isFalse();

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        List.of(),
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNBOUNDED_RANGE"));
  }

  @Test
  void anUnboundedEntityIsStillCappedByRowsAndByGroupKeys() {
    EntityDescriptor positions = registry.findByName("positions").orElseThrow();

    // Dropping the date requirement must not drop the other rails with it.
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        List.of(),
                        null,
                        List.of(
                            new GroupSpec("product_id", null, "a"),
                            new GroupSpec("id_zone", null, "b"),
                            new GroupSpec("id_line", null, "c"),
                            new GroupSpec("position_name", null, "d")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    positions))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void tooManyAggregatesAreRefused() {
    List<AggregateSpec> many = new ArrayList<>();
    for (int i = 0; i < 11; i++) {
      many.add(new AggregateSpec("count", null, "n" + i));
    }
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(window(), null, List.of(new GroupSpec("status", null, "s")), many),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void unknownNamesAreRejectedWhereverTheyAppear() {
    List<Filter> withBadFilter = window();
    withBadFilter.add(new Filter("nope", "eq", "x"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        withBadFilter,
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .describedAs("filter")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("nope", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .describedAs("group key")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("sum", "nope", "n"))),
                    orders))
        .describedAs("aggregate field")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void bucketsAreRejectedOnNonDatesAndWhenTheNameIsNotAKnownBucket() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", "day", "d")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .describedAs("bucket on an enum")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_BUCKET"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("created_at", "fortnight", "d")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .describedAs("bucket nobody defined")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_BUCKET"));
  }

  @Test
  void anUnknownAggregateOpIsRefused() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("median", "items.quantity", "m"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_AGGREGATION"));
  }

  @Test
  void aggregatingAnArrayMemberStillNeedsTheUnwind() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("sum", "items.quantity", "u"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNWIND_REQUIRED"));
  }

  @Test
  void noGroupKeysCollapsesToASingleRow() {
    List<Document> pipeline =
        pipelineOf(
            request(window(), null, List.of(), List.of(new AggregateSpec("count", null, "total"))),
            orders);

    // "How many orders in total" — one row for the whole match, so _id is null.
    assertThat(stage(pipeline, "$group")).containsEntry("_id", null);
    assertThat(stage(pipeline, "$sort")).containsEntry("total", 1);
  }

  @Test
  void sortAcceptsAscendingAndRejectsAMissingFieldName() {
    EntityQueryRequest ascending =
        new EntityQueryRequest(
            window(),
            List.of(new SortSpec("n", "asc")),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("status", null, "s")),
            List.of(new AggregateSpec("count", null, "n")),
            null);
    assertThat(stage(pipelineOf(ascending, orders), "$sort")).containsEntry("n", 1);

    EntityQueryRequest nameless =
        new EntityQueryRequest(
            window(),
            List.of(new SortSpec(null, "asc")),
            List.of(),
            null,
            null,
            null,
            List.of(new GroupSpec("status", null, "s")),
            List.of(new AggregateSpec("count", null, "n")),
            null);
    assertThatThrownBy(() -> translator.translate(nameless, orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_FIELD"));
  }

  @Test
  void filtersThatCannotBoundTheRangeAreSkippedWithoutMaskingTheRealError() {
    // requireBoundedRange walks every filter looking for a usable date bound. Non-date fields and
    // unparseable values must be stepped over rather than thrown on, so that a request carrying
    // both a valid window and a junk value still reaches the real validation.
    List<Filter> noisy = new ArrayList<>(window());
    noisy.add(new Filter("status", "eq", "COMPLETED"));
    noisy.add(new Filter("completed_at", "gte", "not-a-date"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        noisy,
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .describedAs("the junk value is reported as such, not as a missing window")
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_FILTER_VALUE"));

    // Same for an operator nobody defined: it surfaces as an operator problem, not as
    // UNBOUNDED_RANGE, which is what would happen if the bound scan threw on the way past.
    List<Filter> badOperator = new ArrayList<>(window());
    badOperator.add(new Filter("created_at", "banana", "2026-08-01T00:00:00Z"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        badOperator,
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNSUPPORTED_OPERATOR"));
  }

  @Test
  void theTightestBoundsWin() {
    // Several date filters can be present; the narrowest pair defines the window that gets
    // measured against the cap, otherwise a wide-and-narrow pair would be judged on the wide one.
    List<Filter> wideAndNarrow =
        List.of(
            new Filter("created_at", "gte", "2026-01-01T00:00:00Z"),
            new Filter("created_at", "gte", "2026-08-01T00:00:00Z"),
            new Filter("created_at", "lt", "2026-12-01T00:00:00Z"),
            new Filter("created_at", "lt", "2026-08-20T00:00:00Z"));

    assertThatCode(
            () ->
                translator.translate(
                    request(
                        wideAndNarrow,
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .doesNotThrowAnyException();
  }

  @Test
  void anInvertedWindowIsRefused() {
    List<Filter> backwards =
        List.of(
            new Filter("created_at", "gte", "2026-08-20T00:00:00Z"),
            new Filter("created_at", "lt", "2026-08-01T00:00:00Z"));

    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        backwards,
                        null,
                        List.of(new GroupSpec("status", null, "s")),
                        List.of(new AggregateSpec("count", null, "n"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("QUERY_TOO_BROAD"));
  }

  @Test
  void twoAggregatesCannotShareAnOutputName() {
    assertThatThrownBy(
            () ->
                translator.translate(
                    request(
                        window(),
                        "items",
                        List.of(new GroupSpec("items.sku", null, "sku")),
                        List.of(
                            new AggregateSpec("sum", "items.quantity", "n"),
                            new AggregateSpec("count", null, "n"))),
                    orders))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("INVALID_ALIAS"));
  }
}
