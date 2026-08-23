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
                  FieldDescriptor.of("requestedByUserId", FieldType.STRING),
                  FieldDescriptor.of("destinationArea", FieldType.STRING),
                  FieldDescriptor.of("assignedVehicleId", FieldType.STRING),
                  FieldDescriptor.of("createdAt", FieldType.INSTANT),
                  FieldDescriptor.of("startedAt", FieldType.INSTANT),
                  FieldDescriptor.of("completedAt", FieldType.INSTANT),
                  FieldDescriptor.of("cancelReason", FieldType.STRING)),
              "createdAt"),
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
              "createdAt"),
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
                  FieldDescriptor.of("lastSeenAt", FieldType.INSTANT)),
              "name"),
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
              "email"));

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
