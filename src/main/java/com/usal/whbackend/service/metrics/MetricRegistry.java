package com.usal.whbackend.service.metrics;

import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.telemetry.OtelTelemetryAdapter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The single source of truth for what may be queried.
 *
 * <p>Serving {@code GET /metrics/catalog} and validating {@code POST /metrics/query} from the same
 * list is what removes the injection surface: a metric, dimension or aggregation absent here simply
 * cannot reach VictoriaMetrics.
 *
 * <p>Deliberately holds one entry. The pipeline ships proven end to end by a single signal;
 * additional metrics are new entries here plus a recording call in {@code TelemetryPort}.
 */
@Component
public class MetricRegistry {

  private static final Set<UserRole> READERS =
      Set.of(
          UserRole.SUPERADMIN, UserRole.ADMIN_SYSTEM, UserRole.ADMIN_WAREHOUSE, UserRole.DASHBOARD);

  private final List<MetricDescriptor> descriptors =
      List.of(
          new MetricDescriptor(
              OtelTelemetryAdapter.BATTERY_METRIC,
              "wh_vehicle_battery",
              "Vehicle battery",
              "%",
              MetricType.GAUGE,
              List.of("vehicle_id"),
              List.of(Aggregation.AVG, Aggregation.MIN, Aggregation.MAX, Aggregation.LAST),
              READERS));

  public List<MetricDescriptor> all() {
    return descriptors;
  }

  public Optional<MetricDescriptor> findByName(String name) {
    return descriptors.stream().filter(d -> d.name().equals(name)).findFirst();
  }

  /** The catalogue as a given caller may see it — metrics they cannot query are not listed. */
  public List<MetricDescriptor> visibleTo(Set<UserRole> roles) {
    return descriptors.stream().filter(d -> d.isVisibleTo(roles)).toList();
  }
}
