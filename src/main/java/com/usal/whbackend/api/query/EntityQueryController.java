package com.usal.whbackend.api.query;

import com.usal.whbackend.api.Pagination;
import com.usal.whbackend.api.Roles;
import com.usal.whbackend.service.query.EntityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/query")
@Tag(name = "Query", description = "Whitelisted entity queries for dashboards")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("isAuthenticated()")
public class EntityQueryController {

  private final EntityQueryService entityQueryService;

  public EntityQueryController(EntityQueryService entityQueryService) {
    this.entityQueryService = entityQueryService;
  }

  @Operation(
      summary = "List queryable entities",
      description =
          "Self-describing catalogue: each entity lists its queryable fields, their types and the"
              + " operators each field permits. Mirrors GET /metrics/catalog.")
  @ApiResponse(responseCode = "200", description = "Entities visible to the caller")
  @GetMapping("/catalog")
  public ResponseEntity<Map<String, Object>> catalog(Authentication authentication) {
    var entities =
        entityQueryService.catalog(Roles.of(authentication)).stream()
            .map(EntityDescriptorResponse::from)
            .toList();
    return ResponseEntity.ok(Map.of("entities", entities));
  }

  @Operation(summary = "Query an entity", description = "Filtered, sorted, paginated records.")
  @ApiResponse(responseCode = "200", description = "Matching records")
  @ApiResponse(
      responseCode = "400",
      description =
          "UNKNOWN_ENTITY, UNKNOWN_FIELD, UNSUPPORTED_OPERATOR, TOO_MANY_FILTERS, INVALID_FILTER_VALUE")
  @PostMapping("/{entity}")
  public ResponseEntity<Map<String, Object>> query(
      @PathVariable String entity,
      @Valid @RequestBody EntityQueryRequest request,
      Authentication authentication) {
    Page<Map<String, Object>> result =
        entityQueryService.query(entity, request, Roles.of(authentication));
    return ResponseEntity.ok(
        Map.of("items", result.getContent(), "pagination", Pagination.from(result)));
  }
}
