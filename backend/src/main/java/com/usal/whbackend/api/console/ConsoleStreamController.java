package com.usal.whbackend.api.console;

import com.usal.whbackend.api.Roles;
import com.usal.whbackend.config.JwtAuthFilter;
import com.usal.whbackend.config.RequestIdFilter;
import com.usal.whbackend.service.console.stream.ConsoleStreamService;
import com.usal.whbackend.service.console.stream.StreamRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

/**
 * The console's live data, pushed: one Server-Sent Events response carrying many subscriptions. The
 * JWT comes in the Authorization header like every other call (never in the URL), which is why this
 * is a {@code POST} read with fetch rather than an {@code EventSource}.
 */
@RestController
@RequestMapping("/console/stream")
@Tag(name = "Console", description = "Backend console (SUPERADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
@PreAuthorize("hasRole('SUPERADMIN')")
public class ConsoleStreamController {

  private final ConsoleStreamService service;

  public ConsoleStreamController(ConsoleStreamService service) {
    this.service = service;
  }

  @Operation(
      summary = "Push stream",
      description =
          "Opens a text/event-stream. Body: subscriptions[] of {id, kind, ...params}; id matches"
              + " [A-Za-z0-9_.-]{1,64} and is unique; at most 200. Kinds: metric {metric, range,"
              + " step, filters?, group_by?, agg} (as POST /metrics/query with range"
              + " 5m|15m|1h|6h|24h instead of from/to), logs {service?, level?, request_id?, text?,"
              + " range, limit?} (as POST /console/logs/query), ws-events {feed:"
              + " orders|user-orders|vehicles|stock-alerts}, status {}. Events: snapshot (first"
              + " frame of a subscription), update (mode replace for metric/status, append for"
              + " logs/ws-events), error {id, code, message}, end {reason: token-expired |"
              + " server-shutdown | limit}; data is {id, mode, data} with the REST payload."
              + " Heartbeat ': ping' every 15 s. At most 10 streams per user (the user's oldest is ended with"
              + " limit when a new one exceeds that) and 50 in total.")
  @io.swagger.v3.oas.annotations.parameters.RequestBody(
      content = @Content(schema = @Schema(implementation = StreamRequest.class)))
  @ApiResponse(
      responseCode = "200",
      description = "The stream",
      content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_SUBSCRIPTION, INVALID_FILTER_VALUE, MALFORMED_REQUEST")
  @ApiResponse(
      responseCode = "429",
      description = "STREAM_LIMIT: the global cap of open streams is reached")
  @PostMapping
  public ResponseEntity<ResponseBodyEmitter> stream(
      @RequestBody StreamRequest request,
      Authentication authentication,
      HttpServletRequest httpRequest) {
    Instant expiresAt =
        httpRequest.getAttribute(JwtAuthFilter.EXPIRES_AT) instanceof Instant instant
            ? instant
            : null;
    ResponseBodyEmitter emitter =
        service.open(
            request,
            authentication.getName(),
            Roles.of(authentication),
            expiresAt,
            MDC.get(RequestIdFilter.MDC_KEY));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE)
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(emitter);
  }
}
