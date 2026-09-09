package com.usal.whbackend.domain;

public enum UserRole {
  SUPERADMIN,
  ADMIN_SYSTEM,
  ADMIN_WAREHOUSE,
  ADMIN_SALES,
  PROVIDER,
  DISPATCHER,
  OPERATOR,

  /**
   * Read-only consumer of the metrics and entity-query APIs (Grupo 3 — Dashboard y Monitoreo).
   *
   * <p>Deliberately grants no mutation anywhere: it appears in no write endpoint's
   * {@code @PreAuthorize}, and the entity-query catalogue exposes only the entities a dashboard
   * needs. Added so dashboards need not authenticate as an administrator that can create users or
   * delete products.
   */
  DASHBOARD
}
