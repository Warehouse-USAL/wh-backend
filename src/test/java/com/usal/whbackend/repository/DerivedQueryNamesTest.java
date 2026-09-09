package com.usal.whbackend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.usal.whbackend.domain.Line;
import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.Vehicle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.query.parser.PartTree;

/**
 * Guards every derived (method-name-parsed) repository query against a typo in a property name.
 *
 * <p>No test in this project boots a Spring context, so nothing else validates these method names:
 * Spring Data resolves them at bean-creation time, which means a query naming a property that does
 * not exist compiles cleanly, passes the whole test suite, and then fails the application at
 * startup in whatever environment deploys it first. {@link PartTree} is the same parser Spring Data
 * uses, so running it here moves that failure from production startup to CI.
 */
class DerivedQueryNamesTest {

  /** Every repository interface, paired with the document type its queries resolve against. */
  private static List<Object[]> repositories() {
    return List.of(
        new Object[] {PositionRepository.class, Position.class},
        new Object[] {LineRepository.class, Line.class},
        new Object[] {ZoneRepository.class, com.usal.whbackend.domain.Zone.class},
        new Object[] {ProductRepository.class, Product.class},
        new Object[] {UserRepository.class, User.class},
        new Object[] {VehicleRepository.class, Vehicle.class},
        new Object[] {OrderMongoRepository.class, Order.class});
  }

  private static List<Object[]> derivedQueryMethods() {
    return repositories().stream()
        .flatMap(
            entry -> {
              Class<?> repository = (Class<?>) entry[0];
              Class<?> domain = (Class<?>) entry[1];
              return Arrays.stream(repository.getDeclaredMethods())
                  // @Query supplies the query directly, so the method name is not parsed.
                  .filter(m -> !m.isAnnotationPresent(Query.class))
                  .map(
                      m ->
                          new Object[] {repository.getSimpleName() + "." + m.getName(), m, domain});
            })
        .toList();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("derivedQueryMethods")
  @DisplayName("every derived query name resolves against its document's properties")
  void derivedQueryNameResolves(String label, Method method, Class<?> domain) {
    assertThatCode(() -> new PartTree(method.getName(), domain))
        .as(
            "%s does not resolve against %s — Spring Data would fail at application startup, "
                + "not in this suite",
            label, domain.getSimpleName())
        .doesNotThrowAnyException();
  }

  @Test
  @DisplayName("the occupied-positions query filters on isActive, productId and currentStock")
  void occupiedQueryFiltersOnTheExpectedProperties() {
    PartTree tree =
        new PartTree(
            "findByIsActiveTrueAndProductIdNotNullAndCurrentStockGreaterThan", Position.class);

    assertThat(tree.getParts())
        .extracting(part -> part.getProperty().toDotPath() + " " + part.getType().name())
        .containsExactlyInAnyOrder(
            "isActive TRUE", "productId IS_NOT_NULL", "currentStock GREATER_THAN");
  }

  @Test
  @DisplayName("the parser really does reject an unknown property")
  void unknownPropertyIsRejected() {
    // Proves the assertions above have teeth rather than passing vacuously.
    assertThatThrownBy(() -> new PartTree("findByNoSuchProperty", Position.class))
        .isInstanceOf(org.springframework.data.core.PropertyReferenceException.class);
  }
}
