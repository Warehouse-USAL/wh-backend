package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.domain.Order;
import com.usal.whbackend.domain.OrderItem;
import com.usal.whbackend.domain.OrderStatus;
import com.usal.whbackend.domain.Position;
import com.usal.whbackend.domain.Product;
import com.usal.whbackend.domain.ProductCategory;
import com.usal.whbackend.domain.User;
import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoDatasetTest {

  private DemoData data;

  @BeforeEach
  void setUp() {
    // Fake hasher so the test stays free of BCrypt/Spring.
    data = new DemoDataset(p -> "hashed:" + p).build();
  }

  @Test
  void buildsThirteenNonSuperadminUsersAllActiveAndHashed() {
    var users = data.getUsers();
    assertThat(users).hasSize(13);
    assertThat(users).allMatch(User::isActive);
    assertThat(users).noneMatch(u -> u.getRole() == UserRole.SUPERADMIN);
    assertThat(users)
        .allMatch(u -> u.getPasswordHash().equals("hashed:" + DemoDataset.SHARED_PASSWORD));
    assertThat(users).allMatch(u -> u.getEmail().endsWith("@smartwarehouse.local"));
    assertThat(users.stream().map(User::getEmail).distinct().count()).isEqualTo(13);
  }

  @Test
  void usersCoverEveryNonSuperadminRoleWithExpectedCounts() {
    Map<UserRole, Long> byRole =
        data.getUsers().stream()
            .collect(Collectors.groupingBy(User::getRole, Collectors.counting()));
    assertThat(byRole.get(UserRole.ADMIN_SYSTEM)).isEqualTo(1);
    assertThat(byRole.get(UserRole.ADMIN_WAREHOUSE)).isEqualTo(1);
    assertThat(byRole.get(UserRole.ADMIN_SALES)).isEqualTo(1);
    assertThat(byRole.get(UserRole.PROVIDER)).isEqualTo(2);
    assertThat(byRole.get(UserRole.DISPATCHER)).isEqualTo(2);
    assertThat(byRole.get(UserRole.OPERATOR)).isEqualTo(5);
  }

  @Test
  void buildsTwentyFourProductsSixPerCategory() {
    var products = data.getProducts();
    assertThat(products).hasSize(24);
    Map<String, Long> byCategory =
        products.stream()
            .collect(Collectors.groupingBy(Product::getCategory, Collectors.counting()));
    for (ProductCategory category : ProductCategory.values()) {
      assertThat(byCategory.get(category.name())).as("count for %s", category).isEqualTo(6L);
    }
    assertThat(products).allMatch(Product::isActive);
    assertThat(products).allMatch(p -> p.getMaxQuantityPerOrder() > 0);
    assertThat(products).allMatch(p -> p.getPrice() != null && p.getPrice().getAmountCents() > 0);
    assertThat(products).allMatch(p -> p.getImages() != null && !p.getImages().isEmpty());
    assertThat(products.stream().map(Product::getSku).distinct().count()).isEqualTo(24);
  }

  @Test
  void warehouseHasExpectedZonesLinesAndPositions() {
    assertThat(data.getZones()).hasSize(2);
    assertThat(data.getZones())
        .extracting(z -> z.getZoneCode())
        .containsExactlyInAnyOrder("B", "C");
    assertThat(data.getZones()).allMatch(z -> z.isActive());
    assertThat(data.getLines()).hasSize(7);
    assertThat(data.getPositions()).hasSize(35);
  }

  @Test
  void everyPositionReferencesValidZoneLineAndOptionalProduct() {
    Set<String> zoneIds = data.getZones().stream().map(z -> z.getId()).collect(Collectors.toSet());
    Set<String> lineIds = data.getLines().stream().map(l -> l.getId()).collect(Collectors.toSet());
    Set<String> productIds =
        data.getProducts().stream().map(Product::getId).collect(Collectors.toSet());
    for (Position pos : data.getPositions()) {
      assertThat(zoneIds).contains(pos.getIdZone());
      assertThat(lineIds).contains(pos.getIdLine());
      assertThat(pos.getCurrentStock()).isGreaterThanOrEqualTo(0);
      if (pos.getProductId() != null) {
        assertThat(productIds).contains(pos.getProductId());
      }
    }
  }

  @Test
  void everyProductIsStoredInAtLeastOnePosition() {
    Set<String> hostedProductIds =
        data.getPositions().stream()
            .map(Position::getProductId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    for (Product p : data.getProducts()) {
      assertThat(hostedProductIds).as("product %s is hosted", p.getSku()).contains(p.getId());
    }
  }

  @Test
  void buildsSixVehiclesWithExpectedStatusMix() {
    var vehicles = data.getVehicles();
    assertThat(vehicles).hasSize(6);
    Map<VehicleStatus, Long> byStatus =
        vehicles.stream().collect(Collectors.groupingBy(Vehicle::getStatus, Collectors.counting()));
    assertThat(byStatus.get(VehicleStatus.IDLE)).isEqualTo(2);
    assertThat(byStatus.get(VehicleStatus.BUSY)).isEqualTo(2);
    assertThat(byStatus.get(VehicleStatus.OFFLINE)).isEqualTo(1);
    assertThat(byStatus.get(VehicleStatus.ERROR)).isEqualTo(1);
  }

  @Test
  void seededVehiclesCarryOperationSinceOnlyWhileOnline() {
    for (Vehicle v : data.getVehicles()) {
      if (v.getStatus() == VehicleStatus.OFFLINE || v.getStatus() == VehicleStatus.ERROR) {
        assertThat(v.getOperationSince())
            .as(
                "%s vehicle %s has no ongoing operation window — VehicleTelemetryConsumer and"
                    + " VehicleErrorConsumer both clear operation_since on any transition into"
                    + " OFFLINE or ERROR",
                v.getStatus(), v.getId())
            .isNull();
      } else {
        assertThat(v.getOperationSince())
            .as(
                "vehicle %s (%s) should carry an operation_since for the dashboard to use",
                v.getId(), v.getStatus())
            .isNotNull()
            .isBefore(Instant.now());
      }
    }
  }

  @Test
  void buildsAYearOfOrdersAcrossAllStatuses() {
    var orders = data.getOrders();
    // 25 near-term (unchanged) + 352 historical days * 2/day of COMPLETED/CANCELLED-only history.
    assertThat(orders).hasSize(729);
    Map<OrderStatus, Long> byStatus =
        orders.stream().collect(Collectors.groupingBy(Order::getStatus, Collectors.counting()));
    assertThat(byStatus.get(OrderStatus.PENDING)).isEqualTo(7);
    assertThat(byStatus.get(OrderStatus.IN_PROGRESS)).isEqualTo(2);
    assertThat(byStatus.get(OrderStatus.COMPLETED)).isEqualTo(616);
    assertThat(byStatus.get(OrderStatus.CANCELLED)).isEqualTo(104);
  }

  @Test
  void ordersSpanRoughlyAFullYear() {
    Instant oldest =
        data.getOrders().stream().map(Order::getCreatedAt).min(Instant::compareTo).orElseThrow();
    Instant newest =
        data.getOrders().stream().map(Order::getCreatedAt).max(Instant::compareTo).orElseThrow();

    assertThat(java.time.Duration.between(oldest, newest))
        .isGreaterThan(java.time.Duration.ofDays(360));
  }

  @Test
  void everyPriorityLevelIsRepresented() {
    Map<com.usal.whbackend.domain.OrderPriority, Long> byPriority =
        data.getOrders().stream()
            .collect(Collectors.groupingBy(Order::getPriority, Collectors.counting()));

    for (com.usal.whbackend.domain.OrderPriority priority :
        com.usal.whbackend.domain.OrderPriority.values()) {
      assertThat(byPriority.get(priority)).as("count for %s", priority).isPositive();
    }
  }

  @Test
  void historicalOrdersAreOnlyCompletedOrCancelled() {
    // The near-term batch reaches back at most 13 days; anything older is historical, which never
    // emits PENDING/IN_PROGRESS — a year-old order in either state would be a standing bug.
    Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(14));
    for (Order o : data.getOrders()) {
      if (o.getCreatedAt().isBefore(cutoff)) {
        assertThat(o.getStatus()).isIn(OrderStatus.COMPLETED, OrderStatus.CANCELLED);
      }
    }
  }

  @Test
  void everyOrderReferencesValidUserAndProducts() {
    Set<String> userIds = data.getUsers().stream().map(User::getId).collect(Collectors.toSet());
    Map<String, Product> productById =
        data.getProducts().stream().collect(Collectors.toMap(Product::getId, p -> p));
    for (Order o : data.getOrders()) {
      assertThat(userIds).contains(o.getRequestedByUserId());
      assertThat(o.getItems()).isNotEmpty();
      for (OrderItem item : o.getItems()) {
        Product p = productById.get(item.getProductId());
        assertThat(p).as("order %s references a real product", o.getId()).isNotNull();
        assertThat(item.getSku()).isEqualTo(p.getSku());
        assertThat(item.getQuantity()).isBetween(1, p.getMaxQuantityPerOrder());
      }
    }
  }

  @Test
  void orderLifecycleFieldsAreConsistentWithStatus() {
    for (Order o : data.getOrders()) {
      switch (o.getStatus()) {
        case PENDING -> {
          assertThat(o.getStartedAt()).isNull();
          assertThat(o.getAssignedVehicleId()).isNull();
        }
        case IN_PROGRESS -> {
          assertThat(o.getStartedAt()).isNotNull();
          assertThat(o.getAssignedVehicleId()).isNotNull();
        }
        case COMPLETED -> {
          assertThat(o.getStartedAt()).isNotNull();
          assertThat(o.getCompletedAt()).isNotNull();
          assertThat(o.getCompletedAt()).isAfterOrEqualTo(o.getStartedAt());
        }
        case CANCELLED -> assertThat(o.getCancelReason()).isNotBlank();
      }
    }
  }

  @Test
  void busyVehiclesAndInProgressOrdersAreLinkedBothWays() {
    Map<String, Order> orderById =
        data.getOrders().stream().collect(Collectors.toMap(Order::getId, o -> o));
    Map<String, Vehicle> vehicleById =
        data.getVehicles().stream().collect(Collectors.toMap(Vehicle::getId, v -> v));

    for (Vehicle v : data.getVehicles()) {
      if (v.getStatus() == VehicleStatus.BUSY) {
        assertThat(v.getCurrentOrderId()).isNotNull();
        Order o = orderById.get(v.getCurrentOrderId());
        assertThat(o).isNotNull();
        assertThat(o.getStatus()).isEqualTo(OrderStatus.IN_PROGRESS);
        assertThat(o.getAssignedVehicleId()).isEqualTo(v.getId());
      } else {
        assertThat(v.getCurrentOrderId()).isNull();
      }
    }

    for (Order o : data.getOrders()) {
      if (o.getStatus() == OrderStatus.IN_PROGRESS) {
        Vehicle v = vehicleById.get(o.getAssignedVehicleId());
        assertThat(v).isNotNull();
        assertThat(v.getStatus()).isEqualTo(VehicleStatus.BUSY);
        assertThat(v.getCurrentOrderId()).isEqualTo(o.getId());
      }
    }
  }

  @Test
  void stockSimulationKeepsNetAvailableNonNegativePerProduct() {
    Map<String, Integer> available = availableByProduct();
    Map<String, Integer> reserved = reservedByProduct();
    for (Product p : data.getProducts()) {
      int net = available.getOrDefault(p.getId(), 0) - reserved.getOrDefault(p.getId(), 0);
      assertThat(net).as("net available for %s", p.getSku()).isGreaterThanOrEqualTo(0);
    }
  }

  @Test
  void atLeastOneProductSitsBelowMinimumStock() {
    Map<String, Integer> available = availableByProduct();
    boolean anyLow =
        data.getProducts().stream()
            .anyMatch(p -> available.getOrDefault(p.getId(), 0) < p.getMinimumStock());
    assertThat(anyLow).as("a realistic demo has at least one low-stock product").isTrue();
  }

  private Map<String, Integer> availableByProduct() {
    return data.getPositions().stream()
        .filter(Position::isActive)
        .filter(pos -> pos.getProductId() != null)
        .collect(
            Collectors.groupingBy(
                Position::getProductId, Collectors.summingInt(Position::getCurrentStock)));
  }

  private Map<String, Integer> reservedByProduct() {
    return data.getOrders().stream()
        .filter(
            o -> o.getStatus() == OrderStatus.PENDING || o.getStatus() == OrderStatus.IN_PROGRESS)
        .flatMap(o -> o.getItems().stream())
        .collect(
            Collectors.groupingBy(
                OrderItem::getProductId, Collectors.summingInt(OrderItem::getQuantity)));
  }

  // ── Restock/reception history ───────────────────────────────────────────────

  @Test
  void buildsAYearOfRestockOrdersAndReceptions() {
    assertThat(data.getRestockOrders()).hasSize(73);
    assertThat(data.getReceptions()).hasSize(73);
  }

  @Test
  void everyReceptionReferencesAValidProductAndPosition() {
    Set<String> productIds =
        data.getProducts().stream().map(Product::getId).collect(Collectors.toSet());
    Set<String> positionIds =
        data.getPositions().stream().map(Position::getId).collect(Collectors.toSet());

    for (com.usal.whbackend.domain.Reception r : data.getReceptions()) {
      assertThat(productIds).contains(r.getProductId());
      assertThat(r.getAssignments()).isNotEmpty();
      for (var assignment : r.getAssignments()) {
        assertThat(positionIds).contains(assignment.getPositionId());
      }
      int assigned =
          r.getAssignments().stream()
              .mapToInt(com.usal.whbackend.domain.Reception.Assignment::getQuantity)
              .sum();
      assertThat(assigned).isEqualTo(r.getQuantityReceived());
    }
  }

  @Test
  void mostReceptionsLinkBackToARestockOrderButNotAll() {
    Set<String> restockOrderIds =
        data.getRestockOrders().stream()
            .map(com.usal.whbackend.domain.RestockOrder::getId)
            .collect(Collectors.toSet());
    var receptions = data.getReceptions();

    long linked = receptions.stream().filter(r -> r.getRestockOrderId() != null).count();
    long unlinked = receptions.size() - linked;

    assertThat(linked).as("most receptions reference a restock order").isGreaterThan(0);
    assertThat(unlinked).as("a reception need not reference an order").isGreaterThan(0);
    receptions.stream()
        .map(com.usal.whbackend.domain.Reception::getRestockOrderId)
        .filter(id -> id != null)
        .forEach(id -> assertThat(restockOrderIds).contains(id));
  }

  @Test
  void receptionHistorySpansRoughlyAFullYear() {
    Instant oldest =
        data.getReceptions().stream()
            .map(com.usal.whbackend.domain.Reception::getCreatedAt)
            .min(Instant::compareTo)
            .orElseThrow();
    Instant newest =
        data.getReceptions().stream()
            .map(com.usal.whbackend.domain.Reception::getCreatedAt)
            .max(Instant::compareTo)
            .orElseThrow();

    assertThat(java.time.Duration.between(oldest, newest))
        .isGreaterThan(java.time.Duration.ofDays(350));
  }
}
