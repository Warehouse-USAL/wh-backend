package com.usal.whbackend.service.console;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code console.*}: where the backend console reads from. */
@Component
@ConfigurationProperties(prefix = "console")
public class ConsoleProperties {

  private String victorialogsUrl = "http://victorialogs:9428";

  public String getVictorialogsUrl() {
    return victorialogsUrl;
  }

  public void setVictorialogsUrl(String victorialogsUrl) {
    this.victorialogsUrl = victorialogsUrl;
  }
}
