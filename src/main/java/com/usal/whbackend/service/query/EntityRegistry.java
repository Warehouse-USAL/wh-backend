package com.usal.whbackend.service.query;

import com.usal.whbackend.domain.UserRole;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for what may be queried, and by whom.
 *
 * <p>Per-entity roles mirror the table already published in the RFC rather than inventing a
 * parallel permission model: {@code /users} stays {@code admin_system}, products and orders stay
 * broadly readable. DASHBOARD is added only where a dashboard has a legitimate need.
 */
@Component
public class EntityRegistry {

  private static final Set<UserRole> ALL_STAFF =
      Set.of(
          UserRole.SUPERADMIN,
          UserRole.ADMIN_SYSTEM,
          UserRole.ADMIN_WAREHOUSE,
          UserRole.ADMIN_SALES,
          UserRole.DISPATCHER,
          UserRole.OPERATOR,
          UserRole.PROVIDER,
          UserRole.DASHBOARD);

  private static final Set<UserRole> WAREHOUSE_ADMINS =
      Set.of(
          UserRole.SUPERADMIN, UserRole.ADMIN_SYSTEM, UserRole.ADMIN_WAREHOUSE, UserRole.DASHBOARD);

  // Mirrors `GET /users` in the RFC: admin_system only. DASHBOARD is deliberately absent.
  private static final Set<UserRole> SYSTEM_ADMINS =
      Set.of(UserRole.SUPERADMIN, UserRole.ADMIN_SYSTEM);

  private final List<EntityDescriptor> descriptors =
      List.of(
          new EntityDescriptor(
              "orders",
              "orders",
              ALL_STAFF,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("status", FieldType.ENUM),
                  FieldDescriptor.of("priority", FieldType.ENUM),
                  FieldDescriptor.of("requestedByUserId", FieldType.STRING),
                  FieldDescriptor.of("destinationArea", FieldType.STRING),
                  FieldDescriptor.of("assignedVehicleId", FieldType.STRING),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT),
                  FieldDescriptor.of("startedAt", FieldType.INSTANT),
                  FieldDescriptor.of("completedAt", FieldType.INSTANT),
                  FieldDescriptor.of("cancelReason", FieldType.STRING),

                  // Reachable only through `unwind: items`. Without these, demand per SKU has no
                  // source at all: the quantities live one level down, inside the line items.
                  FieldDescriptor.inArray("items.sku", FieldType.STRING),
                  FieldDescriptor.inArray("items.productId", FieldType.STRING),
                  FieldDescriptor.inArray("items.quantity", FieldType.NUMBER),

                  // Durations, in milliseconds. Computed server-side because the subtraction has
                  // to happen before the group — you cannot average a difference that does not
                  // exist yet. Null for orders that never reached that stage, and $avg skips
                  // nulls, so an incomplete order lowers no average.
                  FieldDescriptor.derived(
                      "cycleTimeMs", FieldType.NUMBER, "completedAt", "createdAt"),
                  FieldDescriptor.derived(
                      "assignmentLatencyMs", FieldType.NUMBER, "startedAt", "createdAt")),
              "createdAt",
              Set.of("items"),
              // Append-only and unbounded: the one collection where a missing date filter really
              // could scan everything ever recorded.
              true),
          new EntityDescriptor(
              "products",
              "products",
              ALL_STAFF,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("sku", FieldType.STRING),
                  FieldDescriptor.of("name", FieldType.STRING),
                  FieldDescriptor.of("category", FieldType.STRING),
                  FieldDescriptor.of("active", FieldType.BOOLEAN),
                  FieldDescriptor.of("minimumStock", FieldType.NUMBER),
                  FieldDescriptor.of("maxQuantityPerOrder", FieldType.NUMBER),
                  FieldDescriptor.of("weight", FieldType.NUMBER),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT)),
              "createdAt",
              Set.of(),
              // Catalogue size, not event volume. A date window here would exclude everything
              // catalogued before it rather than bounding anything.
              false),
          new EntityDescriptor(
              "vehicles",
              "vehicles",
              WAREHOUSE_ADMINS,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("name", FieldType.STRING),
                  FieldDescriptor.of("status", FieldType.ENUM),
                  FieldDescriptor.of("battery", FieldType.NUMBER),
                  FieldDescriptor.of("positionX", FieldType.NUMBER),
                  FieldDescriptor.of("positionY", FieldType.NUMBER),
                  FieldDescriptor.of("currentOrderId", FieldType.STRING),
                  FieldDescriptor.of("lastSeenAt", FieldType.INSTANT),
                  FieldDescriptor.of("operationSince", FieldType.INSTANT)),
              "name",
              Set.of(),
              false),
          // Stock on hand lives here, not on the product: a product's quantity is the sum of
          // currentStock across the positions holding it. Without this entity there is no way to
          // ask how much of anything is in the warehouse.
          new EntityDescriptor(
              "positions",
              "positions",
              WAREHOUSE_ADMINS,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("positionName", FieldType.STRING),
                  FieldDescriptor.of("productId", FieldType.STRING),
                  FieldDescriptor.of("currentStock", FieldType.NUMBER),
                  FieldDescriptor.of("maximumCapacity", FieldType.NUMBER),
                  FieldDescriptor.of("idZone", FieldType.STRING),
                  FieldDescriptor.of("idLine", FieldType.STRING),
                  FieldDescriptor.of("isActive", FieldType.BOOLEAN),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT)),
              "positionName",
              Set.of(),
              // Critical: stock on hand is the sum of currentStock over EVERY position. Forcing a
              // date window would drop every pallet racked before it and understate the total
              // with no error at all.
              false),
          // Stock IN events (see Reception's javadoc: the only operation that increases
          // Position.currentStock). Append-only like orders, so it takes the short constructor —
          // bounded range required, no unwindable arrays.
          new EntityDescriptor(
              "receptions",
              "receptions",
              WAREHOUSE_ADMINS,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("productId", FieldType.STRING),
                  FieldDescriptor.of("restockOrderId", FieldType.STRING),
                  FieldDescriptor.of("quantityReceived", FieldType.NUMBER),
                  FieldDescriptor.of("deliveryUnit", FieldType.ENUM),
                  FieldDescriptor.of("supplier", FieldType.STRING),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT)),
              "createdAt"),
          new EntityDescriptor(
              "users",
              "users",
              SYSTEM_ADMINS,
              List.of(
                  FieldDescriptor.of("id", FieldType.STRING),
                  FieldDescriptor.of("email", FieldType.STRING),
                  FieldDescriptor.of("name", FieldType.STRING),
                  FieldDescriptor.of("role", FieldType.ENUM),
                  FieldDescriptor.of("active", FieldType.BOOLEAN),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT),
                  // Neither readable, sortable nor filterable. Filtering matters as much as
                  // projection here: a filterable hash can be recovered one comparison at a time.
                  FieldDescriptor.hidden("passwordHash", FieldType.STRING)),
              "email",
              Set.of(),
              false));

  public List<EntityDescriptor> all() {
    return descriptors;
  }

  public Optional<EntityDescriptor> findByName(String name) {
    return descriptors.stream().filter(d -> d.name().equals(name)).findFirst();
  }

  public List<EntityDescriptor> visibleTo(Set<UserRole> roles) {
    return descriptors.stream().filter(d -> d.isVisibleTo(roles)).toList();
  }
}
