package com.usal.whbackend.service.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.usal.whbackend.api.query.EntityQueryRequest;
import com.usal.whbackend.domain.UserRole;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class EntityQueryServiceTest {

  @Mock MongoTemplate mongoTemplate;

  private final EntityRegistry registry = new EntityRegistry();
  private final CriteriaTranslator translator = new CriteriaTranslator();

  private EntityQueryService service() {
    return new EntityQueryService(registry, translator, mongoTemplate);
  }

  private static EntityQueryRequest empty() {
    return new EntityQueryRequest(List.of(), List.of(), List.of(), 0, 25);
  }

  private static String codeOf(Throwable t) {
    return ((ResponseStatusException) t).getReason();
  }

  @Test
  void returnsSnakeCaseKeysAndIsoInstantsToMatchTheRestOfTheApi() {
    Document raw =
        new Document("_id", "ORD-1")
            .append("status", "COMPLETED")
            .append("assignedVehicleId", "VHC-001")
            .append("createdAt", Date.from(Instant.parse("2026-08-01T00:00:00Z")));
    when(mongoTemplate.count(any(Query.class), anyString())).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Document.class), anyString()))
        .thenReturn(List.of(raw));

    Page<Map<String, Object>> page = service().query("orders", empty(), Set.of(UserRole.DASHBOARD));

    Map<String, Object> item = page.getContent().get(0);
    assertThat(item).containsEntry("id", "ORD-1");
    assertThat(item).containsEntry("assigned_vehicle_id", "VHC-001");
    assertThat(item.get("created_at")).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    assertThat(page.getTotalElements()).isEqualTo(1);
  }

  @Test
  void neverEmitsAFieldOutsideTheWhitelistEvenIfTheDocumentCarriesOne() {
    // The document holds passwordHash; the response is assembled from the whitelist, so it cannot
    // appear regardless of what the projection did.
    Document raw =
        new Document("_id", "USR-1")
            .append("email", "a@b.c")
            .append("name", "A")
            .append("role", "OPERATOR")
            .append("active", true)
            .append("passwordHash", "$2a$10$leaked");
    when(mongoTemplate.count(any(Query.class), anyString())).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Document.class), anyString()))
        .thenReturn(List.of(raw));

    Map<String, Object> item =
        service().query("users", empty(), Set.of(UserRole.ADMIN_SYSTEM)).getContent().get(0);

    assertThat(item).doesNotContainKey("passwordHash");
    assertThat(item).doesNotContainKey("password_hash");
    assertThat(item).containsEntry("email", "a@b.c");
  }

  @Test
  void rendersObjectIdAsTheHexStringEveryOtherEndpointReturns() {
    org.bson.types.ObjectId oid = new org.bson.types.ObjectId("507f1f77bcf86cd799439011");
    Document raw = new Document("_id", oid).append("email", "a@b.c").append("name", "A");
    when(mongoTemplate.count(any(Query.class), anyString())).thenReturn(1L);
    when(mongoTemplate.find(any(Query.class), eq(Document.class), anyString()))
        .thenReturn(List.of(raw));

    Map<String, Object> item =
        service().query("users", empty(), Set.of(UserRole.ADMIN_SYSTEM)).getContent().get(0);

    // Not a {date, timestamp} object — collections whose @Id is not a String hand back an ObjectId.
    assertThat(item.get("id")).isEqualTo("507f1f77bcf86cd799439011");
  }

  @Test
  void rejectsUnknownEntityWithoutTouchingMongo() {
    assertThatThrownBy(() -> service().query("secrets", empty(), Set.of(UserRole.SUPERADMIN)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_ENTITY"));
    verify(mongoTemplate, never()).find(any(), any(), anyString());
  }

  @Test
  void dashboardCannotReachTheUsersCollection() {
    // Mirrors the RFC: GET /users is admin_system only. Reported as unknown, not forbidden.
    assertThatThrownBy(() -> service().query("users", empty(), Set.of(UserRole.DASHBOARD)))
        .satisfies(t -> assertThat(codeOf(t)).isEqualTo("UNKNOWN_ENTITY"));
    verify(mongoTemplate, never()).find(any(), any(), anyString());
  }

  @Test
  void catalogIsScopedToTheCallersRoles() {
    assertThat(service().catalog(Set.of(UserRole.DASHBOARD)))
        .extracting(EntityDescriptor::name)
        .containsExactlyInAnyOrder("orders", "products", "vehicles");
    assertThat(service().catalog(Set.of(UserRole.ADMIN_SYSTEM)))
        .extracting(EntityDescriptor::name)
        .contains("users");
    assertThat(service().catalog(Set.of())).isEmpty();
  }
}
