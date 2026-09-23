package com.usal.whbackend.service.metrics;

import com.usal.whbackend.domain.UserRole;
import com.usal.whbackend.service.metrics.restock.RestockParams;
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
 * <p>{@link #all()} holds raw fleet signals only. Nothing derived — no MTBF, no failure rate —
 * because those are ratios of these series and belong to whoever is drawing the chart. Adding one
 * is an entry here plus a recording call in {@code TelemetryPort}.
 *
 * <p>{@link #computed()} is the other kind: business metrics whose formula is the point, so the
 * backend owns it and every team gets the same number. Each is served by its own endpoint; adding
 * one is an entry here plus that endpoint.
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

  private final List<ComputedMetricDescriptor> computed =
      List.of(
          new ComputedMetricDescriptor(
              "restock_suggestions",
              "/metrics/restock-suggestions",
              "Sugerencia de reposición",
              "Demanda diaria ponderada y, por producto, si hay que reponer y cuánto.",
              List.of(
                  number(
                      "alpha", 0.0, 1.0, "Peso de la demanda reciente frente a la de largo plazo"),
                  integer(
                      "recent_days",
                      1,
                      RestockParams.MAX_WINDOW_DAYS - 1,
                      "Días hacia atrás que cuentan como período reciente"),
                  integer(
                      "long_days",
                      2,
                      RestockParams.MAX_WINDOW_DAYS,
                      "Días hacia atrás del período largo; debe ser mayor que recent_days"),
                  number(
                      "safety_days", 0.0, null, "Días de demanda que cubre el stock de seguridad"),
                  number("lead_time_days", 0.0, null, "Días que tarda en llegar una reposición"),
                  number(
                      "coverage_days",
                      0.0,
                      null,
                      "Días de stock que se busca cubrir después de reponer")),
              List.of(
                  new ComputedMetricDescriptor.Field(
                      "product_ids",
                      "string[]",
                      null,
                      null,
                      "Limita a estos productos; vacío o ausente = todos los activos"),
                  new ComputedMetricDescriptor.Field(
                      "category", "string", null, null, "Limita a una categoría del catálogo")),
              READERS));

  private static ComputedMetricDescriptor.Field number(
      String name, Double min, Double max, String description) {
    return new ComputedMetricDescriptor.Field(name, "number", min, max, description);
  }

  private static ComputedMetricDescriptor.Field integer(
      String name, int min, int max, String description) {
    return new ComputedMetricDescriptor.Field(
        name, "integer", (double) min, (double) max, description);
  }

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

  public List<ComputedMetricDescriptor> computed() {
    return computed;
  }

  /** Computed metrics a given caller may request. */
  public List<ComputedMetricDescriptor> computedVisibleTo(Set<UserRole> roles) {
    return computed.stream().filter(d -> d.isVisibleTo(roles)).toList();
  }
}
