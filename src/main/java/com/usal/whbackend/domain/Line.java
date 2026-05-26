package com.usal.whbackend.domain;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "lines")
@CompoundIndex(name = "zone_number_idx", def = "{'idZone': 1, 'numberLine': 1}", unique = true)
public class Line {

  @Id private String id;
  private String idZone;
  private int numberLine;
  private boolean isActive;
  private int maxAllowedPositions;
  private Instant createdAt;

  public Line() {}

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getIdZone() {
    return idZone;
  }

  public void setIdZone(String idZone) {
    this.idZone = idZone;
  }

  public int getNumberLine() {
    return numberLine;
  }

  public void setNumberLine(int numberLine) {
    this.numberLine = numberLine;
  }

  public boolean isActive() {
    return isActive;
  }

  public void setActive(boolean active) {
    isActive = active;
  }

  public int getMaxAllowedPositions() {
    return maxAllowedPositions;
  }

  public void setMaxAllowedPositions(int maxAllowedPositions) {
    this.maxAllowedPositions = maxAllowedPositions;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }
}
