package com.usal.whbackend.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds {@code telemetry.*}. Disabled by default so tests and local runs need no collector. */
@ConfigurationProperties(prefix = "telemetry")
public class TelemetryProperties {

  private boolean enabled = false;
  private String endpoint = "http://otel-collector:4318/v1/metrics";
  private String serviceName = "wh-backend";
  private long exportIntervalMs = 10000L;
  private long exportTimeoutMs = 5000L;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getEndpoint() {
    return endpoint;
  }

  public void setEndpoint(String endpoint) {
    this.endpoint = endpoint;
  }

  public String getServiceName() {
    return serviceName;
  }

  public void setServiceName(String serviceName) {
    this.serviceName = serviceName;
  }

  public long getExportIntervalMs() {
    return exportIntervalMs;
  }

  public void setExportIntervalMs(long exportIntervalMs) {
    this.exportIntervalMs = exportIntervalMs;
  }

  public long getExportTimeoutMs() {
    return exportTimeoutMs;
  }

  public void setExportTimeoutMs(long exportTimeoutMs) {
    this.exportTimeoutMs = exportTimeoutMs;
  }
}
