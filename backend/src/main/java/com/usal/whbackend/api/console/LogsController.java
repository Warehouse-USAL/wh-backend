package com.usal.whbackend.api.console;

import com.usal.whbackend.service.console.LogLine;
import com.usal.whbackend.service.console.LogsQuery;
import com.usal.whbackend.service.console.LogsQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Searches backend logs by service, level, request id and text. */
@RestController
@RequestMapping("/console/logs")
@Tag(name = "Console", description = "Backend console (SUPERADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('SUPERADMIN')")
public class LogsController {

  private final LogsQueryService service;

  public LogsController(LogsQueryService service) {
    this.service = service;
  }

  @Operation(
      summary = "Search logs",
      description =
          "Filters: service, level (debug|info|warn|error), request_id, text, from, to, limit"
              + " (default 100, max 500). Range at most 7 days; defaults to the last hour.")
  @PostMapping("/query")
  public ResponseEntity<Map<String, Object>> query(@RequestBody LogsQuery query) {
    List<LogLine> lines = service.query(query);
    return ResponseEntity.ok(Map.of("lines", lines, "total", lines.size()));
  }
}
