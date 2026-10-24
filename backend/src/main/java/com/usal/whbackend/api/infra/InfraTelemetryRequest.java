package com.usal.whbackend.api.infra;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One vehicle reading. Fields this backend does not use (the vehicle id, repeated from the path,
 * and anything Infra adds later) are ignored rather than rejected.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InfraTelemetryRequest(String status, Integer battery, Fault error, String timestamp) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Fault(String code, String message) {}
}
