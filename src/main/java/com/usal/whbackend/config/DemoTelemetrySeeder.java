package com.usal.whbackend.config;

import com.usal.whbackend.domain.Vehicle;
import com.usal.whbackend.domain.VehicleStatus;
import com.usal.whbackend.repository.VictoriaMetricsRepository;
import com.usal.whbackend.repository.VictoriaMetricsRepository.SeriesData;
import com.usal.whbackend.service.metrics.MetricDescriptor;
import com.usal.whbackend.service.metrics.MetricRegistry;
import com.usal.whbackend.telemetry.ErrorCode;
import com.usal.whbackend.telemetry.OtelTelemetryAdapter;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Writes a week of synthetic fleet history into VictoriaMetrics alongside the demo business data.
 *
 * <p>Runs only as part of a demo seed, so it cannot fire on a real deployment. Failure is logged
 * and swallowed: a missing metrics store must never stop the application from starting, and demo
 * history is a convenience rather than anything the system depends on.
 *
 * <p>Series names come from {@link MetricRegistry} rather than being repeated here, so seeded
 * points always land on the same series the live pipeline writes to.
 */
@Component
public class DemoTelemetrySeeder {

  private static final Logger log = LoggerFactory.getLogger(DemoTelemetrySeeder.class);

  /**
   * Series per request. Keeps any single import body to a few hundred kilobytes — the original
   * value of 8 was sized for a week at 5-minute resolution (2,016 points/series). Series are now a
   * year at 30-minute resolution ({@link DemoTelemetryDataset#DAYS} / {@link
   * DemoTelemetryDataset#RESOLUTION}, 17,520 points/series, ~8.7x longer), so this is scaled down
   * by the same factor to keep the per-request payload in the same ballpark.
   */
  private static final int BATCH_SIZE = 1;

  private final VictoriaMetricsRepository victoriaMetrics;
  private final MetricRegistry metricRegistry;

  public DemoTelemetrySeeder(
      VictoriaMetricsRepository victoriaMetrics, MetricRegistry metricRegistry) {
    this.victoriaMetrics = victoriaMetrics;
    this.metricRegistry = metricRegistry;
  }

  public void seed(List<Vehicle> vehicles) {
    try {
      seedOrThrow(vehicles);
    } catch (RuntimeException e) {
      // Belt and braces around a convenience feature. Demo history is worth exactly nothing
      // compared to the application starting, so nothing raised below this line may escape.
      log.warn("Demo telemetry seeding failed and was skipped: {}", e.toString());
    }
  }

  private void seedOrThrow(List<Vehicle> vehicles) {
    if (vehicles.isEmpty()) {
      return;
    }
    Optional<String> state = seriesName(OtelTelemetryAdapter.STATE_METRIC);
    Optional<String> battery = seriesName(OtelTelemetryAdapter.BATTERY_METRIC);
    Optional<String> transitions = seriesName(OtelTelemetryAdapter.TRANSITIONS_METRIC);
    if (state.isEmpty() || battery.isEmpty() || transitions.isEmpty()) {
      log.warn("Demo telemetry skipped: fleet metrics are not in the catalogue.");
      return;
    }

    // Guards against a second seed doubling the history. The business seed already refuses on a
    // non-empty database; this covers a store that survived the database being dropped.
    if (victoriaMetrics.hasAnySeries(state.get())) {
      log.info("Demo telemetry already present in VictoriaMetrics — skipping.");
      return;
    }

    List<SeriesData> series =
        new DemoTelemetryDataset(
                Instant.now(),
                state.get(),
                battery.get(),
                transitions.get(),
                vehicles.stream().map(Vehicle::getId).toList(),
                Arrays.stream(VehicleStatus.values()).map(Enum::name).toList(),
                // Every real category but OTHER, so the seeded Pareto shows a spread rather than
                // a single bar; OTHER is the live fallback for a code this enum does not know yet.
                Arrays.stream(ErrorCode.values())
                    .filter(code -> code != ErrorCode.OTHER)
                    .map(Enum::name)
                    .toList())
            .build();

    if (victoriaMetrics.importSeries(series, BATCH_SIZE)) {
      log.info(
          "Seeded {} days of demo telemetry: {} series across {} vehicles.",
          DemoTelemetryDataset.DAYS,
          series.size(),
          vehicles.size());
    } else {
      log.warn(
          "Demo telemetry could not be written — VictoriaMetrics unreachable or refused the"
              + " import. The dashboard's rover charts will start empty; everything else is"
              + " unaffected.");
    }
  }

  private Optional<String> seriesName(String metricName) {
    return metricRegistry.findByName(metricName).map(MetricDescriptor::seriesName);
  }
}
