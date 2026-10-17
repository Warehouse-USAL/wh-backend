package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "zones")
public class Zone {

  @Id private String id;

  @Indexed(unique = true)
  private String zoneCode;

  private boolean isActive;
  private int maxAllowedLines;
  private Instant createdAt;

  public Zone() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getZoneCode() {
    return zoneCode;
  }

  public void setZoneCode(String zoneCode) {
    this.zoneCode = zoneCode;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    isActive = active;
  }

  public int getMaxAllowedLines() {
    return maxAllowedLines;
  }

  public void setMaxAllowedLines(int maxAllowedLines) {
    this.maxAllowedLines = maxAllowedLines;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
