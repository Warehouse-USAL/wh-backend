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
 * <p>Holds raw fleet signals only. Nothing derived — no MTBF, no failure rate — because those are
 * ratios of these series and belong to whoever is drawing the chart. Adding a metric is an entry
 * here plus a recording call in {@code TelemetryPort}.
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
              READERS),
          new MetricDescriptor(
              OtelTelemetryAdapter.STATE_METRIC,
              "wh_vehicle_state",
              "Vehicle state",
              "1",
              MetricType.GAUGE,
              List.of("vehicle_id", "state"),
              // COUNT answers "how many rovers were in this state at once"; AVG answers "what
              // fraction of the window one rover spent there" — the input to a mean time to
              // recovery, which the caller divides for itself.
              List.of(Aggregation.COUNT, Aggregation.AVG, Aggregation.MAX, Aggregation.LAST),
              READERS),
          new MetricDescriptor(
              OtelTelemetryAdapter.TRANSITIONS_METRIC,
              "wh_vehicle_transitions",
              "Vehicle status transitions",
              "1",
              MetricType.COUNTER,
              List.of("vehicle_id", "from", "to", "category"),
              List.of(Aggregation.INCREASE, Aggregation.RATE),
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
