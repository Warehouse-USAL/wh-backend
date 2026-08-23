package com.usal.whbackend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.usal.whbackend.repository.VictoriaMetricsRepository.SeriesData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DemoTelemetryDatasetTest {

  private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
  private static final List<String> VEHICLES = List.of("v-1", "v-2", "v-3", "v-4", "v-5", "v-6");
  private static final List<String> STATES = List.of("IDLE", "BUSY", "OFFLINE", "ERROR");

  private static List<SeriesData> build() {
    return new DemoTelemetryDataset(
            NOW,
            "wh_vehicle_state",
            "wh_vehicle_battery",
            "wh_vehicle_transitions",
            VEHICLES,
            STATES,
            "UNCATEGORIZED")
        .build();
  }

  private static List<SeriesData> named(List<SeriesData> all, String name) {
    return all.stream().filter(s -> name.equals(s.labels().get("__name__"))).toList();
  }

  @Test
  void isDeterministicSoADemoReplaysIdentically() {
    List<SeriesData> a = build();
    List<SeriesData> b = build();

    assertThat(a).hasSameSizeAs(b);
    for (int i = 0; i < a.size(); i++) {
      assertThat(a.get(i).labels()).isEqualTo(b.get(i).labels());
      assertThat(a.get(i).values()).isEqualTo(b.get(i).values());
      assertThat(a.get(i).timestampsMs()).isEqualTo(b.get(i).timestampsMs());
    }
  }

  @Test
  void everyVehicleIsInExactlyOneStateAtEveryTick() {
    List<SeriesData> state = named(build(), "wh_vehicle_state");
    Map<String, List<SeriesData>> byVehicle =
        state.stream().collect(Collectors.groupingBy(s -> s.labels().get("vehicle_id")));

    assertThat(byVehicle).hasSize(VEHICLES.size());
    byVehicle.forEach(
        (vehicle, series) -> {
          assertThat(series).hasSize(STATES.size());
          int ticks = series.get(0).values().size();
          for (int t = 0; t < ticks; t++) {
            double total = 0;
            for (SeriesData one : series) {
              total += one.values().get(t);
            }
            // A state-set gauge is only meaningful if the states sum to one: otherwise summing
            // across vehicles to count "how many are BUSY" would double-count or lose rovers.
            assertThat(total).describedAs("vehicle %s tick %s", vehicle, t).isEqualTo(1.0);
          }
        });
  }

  @Test
  void transitionCountersNeverDecrease() {
    for (SeriesData series : named(build(), "wh_vehicle_transitions")) {
      List<Double> values = series.values();
      for (int i = 1; i < values.size(); i++) {
        // increase() reads any drop as a counter reset and adds the whole new value, so a
        // non-monotonic seeded counter would invent failures that never happened.
        assertThat(values.get(i))
            .describedAs("%s at %s", series.labels(), i)
            .isGreaterThanOrEqualTo(values.get(i - 1));
      }
    }
  }

  @Test
  void carriesRealFailuresSoMeanTimeBetweenFailuresHasSomethingToCount() {
    List<SeriesData> intoError =
        named(build(), "wh_vehicle_transitions").stream()
            .filter(s -> "ERROR".equals(s.labels().get("to")))
            .toList();

    assertThat(intoError).isNotEmpty();
    double mostFailuresOnOneRover =
        intoError.stream().mapToDouble(s -> s.values().get(s.values().size() - 1)).max().orElse(0);
    assertThat(mostFailuresOnOneRover).isGreaterThan(1.0);
  }

  @Test
  void keepsOneRoverParkedSoConcurrencyIsNotAFlatLine() {
    List<SeriesData> offline =
        named(build(), "wh_vehicle_state").stream()
            .filter(s -> "OFFLINE".equals(s.labels().get("state")))
            .filter(s -> s.values().stream().allMatch(v -> v == 1.0))
            .toList();

    assertThat(offline).hasSize(1);
  }

  @Test
  void batteryStaysInRangeAndSeriesCoverTheAdvertisedWindow() {
    List<SeriesData> all = build();
    List<SeriesData> battery = named(all, "wh_vehicle_battery");

    assertThat(battery).hasSize(VEHICLES.size());
    assertThat(battery).allSatisfy(s -> assertThat(s.values()).allMatch(v -> v >= 0 && v <= 100));

    List<Long> stamps = battery.get(0).timestampsMs();
    assertThat(stamps).isSorted();
    assertThat(stamps.get(stamps.size() - 1)).isLessThanOrEqualTo(NOW.toEpochMilli());
    assertThat(NOW.toEpochMilli() - stamps.get(0))
        .isLessThanOrEqualTo(Duration.ofDays(DemoTelemetryDataset.DAYS).toMillis());
  }

  @Test
  void everySeriesCarriesTheJobLabelTheCollectorAdds() {
    // Seeded points must land on the SAME series the live pipeline writes to. A missing or
    // different job label would silently create a parallel set that no query joins back up.
    assertThat(build())
        .allSatisfy(s -> assertThat(s.labels()).containsEntry("job", DemoTelemetryDataset.JOB));
  }

  @Test
  void valuesAndTimestampsAlwaysLineUp() {
    assertThat(build()).allSatisfy(s -> assertThat(s.values()).hasSameSizeAs(s.timestampsMs()));
  }
}
