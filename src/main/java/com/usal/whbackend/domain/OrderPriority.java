package com.usal.whbackend.domain;

import com.fasterxml.jackson.annotation.JsonValue;

public enum OrderPriority {
  LOW,
  MEDIUM,
  HIGH,
  URGENT;

  @JsonValue
  public String toJson() {
    return name().toLowerCase();
  }
}
