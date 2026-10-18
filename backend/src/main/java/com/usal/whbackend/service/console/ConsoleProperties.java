package com.usal.whbackend.service.console;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code console.*}: where the backend console reads from. */
@Component
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

  private String victorialogsUrl = "http://victorialogs:9428";
  private String bridgeUrl = "http://bridge:8081";
  private String minioUrl = "http://minio:9000";
  private String victoriametricsUrl = "http://victoriametrics:8428";
  private String collectorUrl = "http://otel-collector:13133";

  public String getVictorialogsUrl() {
    return victorialogsUrl;
  }

  public void setVictorialogsUrl(String victorialogsUrl) {
    this.victorialogsUrl = victorialogsUrl;
  }

  public String getBridgeUrl() {
    return bridgeUrl;
  }

  public void setBridgeUrl(String bridgeUrl) {
    this.bridgeUrl = bridgeUrl;
  }

  public String getMinioUrl() {
    return minioUrl;
  }

  public void setMinioUrl(String minioUrl) {
    this.minioUrl = minioUrl;
  }

  public String getVictoriametricsUrl() {
    return victoriametricsUrl;
  }

  public void setVictoriametricsUrl(String victoriametricsUrl) {
    this.victoriametricsUrl = victoriametricsUrl;
  }

  public String getCollectorUrl() {
    return collectorUrl;
  }

  public void setCollectorUrl(String collectorUrl) {
    this.collectorUrl = collectorUrl;
  }
}
