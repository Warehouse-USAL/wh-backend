package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The factory methods that decide what a field may be used for. */
class DescriptorFactoriesTest {

  @Test
  void readOnlyFieldsCanBeReadAndSortedButNeverFiltered() {
    FieldDescriptor f = FieldDescriptor.readOnly("createdAt", FieldType.INSTANT);

    assertThat(f.selectable()).isTrue();
    assertThat(f.sortable()).isTrue();
    assertThat(f.filterable()).isFalse();
    assertThat(f.permittedOperators()).isEmpty();
    // Still aggregatable: min/max over a date answers "when was this last touched".
    assertThat(f.aggregatable()).isTrue();
    assertThat(f.isDerived()).isFalse();
    assertThat(f.isArrayMember()).isFalse();
  }

  @Test
  void hiddenFieldsAreClosedOnEveryAxis() {
    FieldDescriptor f = FieldDescriptor.hidden("passwordHash", FieldType.STRING);

    // Blocking projection alone is not enough: a filterable secret is an oracle that gives up
    // its value one comparison at a time, and a groupable one gives it up in a single response.
    assertThat(f.selectable()).isFalse();
    assertThat(f.sortable()).isFalse();
    assertThat(f.filterable()).isFalse();
    assertThat(f.groupable()).isFalse();
    assertThat(f.aggregatable()).isFalse();
    assertThat(f.permittedOperators()).isEmpty();
  }

  @Test
  void arrayMembersAreQueryableButNotProjectable() {
    FieldDescriptor f = FieldDescriptor.inArray("items.sku", FieldType.STRING);

    assertThat(f.isArrayMember()).isTrue();
    assertThat(f.groupable()).isTrue();
    assertThat(f.filterable()).isTrue();
    assertThat(f.selectable()).isFalse();
    assertThat(f.sortable()).isFalse();
  }

  @Test
  void derivedFieldsAreFilterableSoTheCallerOwnsTheThreshold() {
    FieldDescriptor f =
        FieldDescriptor.derived("cycleTimeMs", FieldType.NUMBER, "completedAt", "createdAt");

    assertThat(f.isDerived()).isTrue();
    assertThat(f.derivation())
        .isEqualTo(new FieldDescriptor.Derivation("completedAt", "createdAt"));
    assertThat(f.aggregatable()).isTrue();
    assertThat(f.filterable()).isTrue();
    // Near-unique values, so grouping on one would return a row per document.
    assertThat(f.groupable()).isFalse();
    assertThat(f.selectable()).isFalse();
  }

  @Test
  void onlyNumbersAndInstantsAreAggregatable() {
    assertThat(FieldDescriptor.of("n", FieldType.NUMBER).aggregatable()).isTrue();
    assertThat(FieldDescriptor.of("t", FieldType.INSTANT).aggregatable()).isTrue();
    assertThat(FieldDescriptor.of("s", FieldType.STRING).aggregatable()).isFalse();
    assertThat(FieldDescriptor.of("e", FieldType.ENUM).aggregatable()).isFalse();
    assertThat(FieldDescriptor.of("b", FieldType.BOOLEAN).aggregatable()).isFalse();
  }

  @Test
  void fieldListsAreCopiedSoADescriptorCannotBeMutatedAfterConstruction() {
    List<FieldDescriptor> copied =
        FieldDescriptor.copy(List.of(FieldDescriptor.of("id", FieldType.STRING)));

    assertThat(copied).hasSize(1);
    assertThat(copied.getClass().getName()).contains("Immutable");
  }

  @Test
  void theShortEntityConstructorUnwindsNothingAndDemandsADateWindow() {
    EntityDescriptor e =
        new EntityDescriptor(
            "things",
            "things",
            Set.of(UserRole.DASHBOARD),
            List.of(FieldDescriptor.of("id", FieldType.STRING)),
            "id");

    assertThat(e.unwindableArrays()).isEmpty();
    assertThat(e.canUnwind("anything")).isFalse();
    // The safe default for an entity nobody has classified yet: bound the scan.
    assertThat(e.requiresBoundedRange()).isTrue();
  }
}
