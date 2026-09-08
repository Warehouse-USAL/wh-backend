package com.usal.whbackend.config;

import com.usal.whbackend.repository.VictoriaMetricsRepository.SeriesData;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a week of synthetic fleet history, so a freshly seeded stack has charts instead of empty
 * panels.
 *
 * <p>The metrics store has no backfill: OpenTelemetry publishes what is happening now, so a
 * dashboard pointed at a stack that booted five minutes ago sees five minutes of data. The demo
 * business data covers three weeks of orders; without this the rover half of the dashboard would be
 * blank next to it.
 *
 * <p>Pure and deterministic — no clock beyond the one passed in, no randomness. The same instant
 * produces the same history, so a demo can be reproduced and this class can be unit tested.
 *
 * <p>The simulation is shaped to make each chart show something: a working day, one rover that is
 * always offline, and one that fails periodically so mean-time-between-failures has failures to
 * count.
 */
public final class DemoTelemetryDataset {

  // A full year at 5-minute resolution (the original resolution) would be ~105k ticks/vehicle —
  // several times the point count VictoriaMetrics' /api/v1/import is comfortable receiving in one
  // seed run, and a proportionally larger in-memory build. 30-minute resolution keeps a year of
  // history smooth enough for any dashboard chart while keeping both bounded, the same trade-off
  // a real long-range retention policy makes by downsampling older data.
  public static final int DAYS = 365;
  public static final Duration RESOLUTION = Duration.ofMinutes(30);

  /** The label the collector attaches, so seeded points land on the same series as live ones. */
  static final String JOB = "wh-backend";

  /** Category recorded for a transition that is not into or out of ERROR. */
  private static final String NON_FAULT_CATEGORY = "UNCATEGORIZED";

  private static final ZoneId LOCAL = ZoneId.of("America/Argentina/Buenos_Aires");
  private static final int SHIFT_START_HOUR = 8;
  private static final int SHIFT_END_HOUR = 20;

  /** Ticks between the start of one simulated fault and the next, on the failing rover. */
  private static final int FAULT_PERIOD_TICKS = 36; // 18h at 30-minute resolution

  private static final int FAULT_LENGTH_TICKS = 2; // 60 minutes

  private final Instant now;
  private final String stateSeries;
  private final String batterySeries;
  private final String transitionsSeries;
  private final List<String> vehicleIds;
  private final List<String> states;
  private final List<String> faultCategories;

  public DemoTelemetryDataset(
      Instant now,
      String stateSeries,
      String batterySeries,
      String transitionsSeries,
      List<String> vehicleIds,
      List<String> states,
      List<String> faultCategories) {
    this.now = now;
    this.stateSeries = stateSeries;
    this.batterySeries = batterySeries;
    this.transitionsSeries = transitionsSeries;
    this.vehicleIds = List.copyOf(vehicleIds);
    this.states = List.copyOf(states);
    this.faultCategories = List.copyOf(faultCategories);
  }

  public List<SeriesData> build() {
    long stepMs = RESOLUTION.toMillis();
    int ticks = (int) (Duration.ofDays(DAYS).toMillis() / stepMs);
    long startMs = now.toEpochMilli() - (long) ticks * stepMs;

    List<SeriesData> out = new ArrayList<>();
    for (int v = 0; v < vehicleIds.size(); v++) {
      out.addAll(buildVehicle(vehicleIds.get(v), v, startMs, stepMs, ticks));
    }
    return out;
  }

  private List<SeriesData> buildVehicle(
      String vehicleId, int index, long startMs, long stepMs, int ticks) {

    Map<String, List<Double>> stateValues = new LinkedHashMap<>();
    for (String state : states) {
      stateValues.put(state, new ArrayList<>(ticks));
    }
    List<Double> battery = new ArrayList<>(ticks);
    List<Long> timestamps = new ArrayList<>(ticks);

    // Counter series are cumulative and sampled hourly: a counter is a step function, and
    // increase() only needs points either side of the window it is asked about.
    Map<String, Long> transitionCounts = new LinkedHashMap<>();
    Map<String, List<Double>> transitionValues = new LinkedHashMap<>();
    List<Long> transitionTimestamps = new ArrayList<>();

    String previous = null;
    double charge = 60 + (index * 7 % 35);

    for (int t = 0; t < ticks; t++) {
      long timestampMs = startMs + (long) t * stepMs;
      String state = stateAt(index, timestampMs, t);

      if (previous != null && !previous.equals(state)) {
        String category = categoryFor(previous, state, t);
        transitionCounts.merge(previous + ">" + state + ">" + category, 1L, Long::sum);
      }
      previous = state;

      charge = chargeAfter(charge, state);
      timestamps.add(timestampMs);
      battery.add(Math.round(charge * 10.0) / 10.0);
      for (String candidate : states) {
        stateValues.get(candidate).add(candidate.equals(state) ? 1.0 : 0.0);
      }

      boolean hourBoundary = (t * stepMs) % Duration.ofHours(1).toMillis() == 0;
      if (hourBoundary || t == ticks - 1) {
        transitionTimestamps.add(timestampMs);
        for (String pair : transitionCounts.keySet()) {
          transitionValues.computeIfAbsent(pair, k -> new ArrayList<>());
        }
        // Every known pair gets a point at every boundary, so a series never has a hole that
        // increase() would read as a counter reset.
        transitionValues.forEach(
            (pair, values) -> {
              while (values.size() < transitionTimestamps.size() - 1) {
                // Carried forward as a reference: the ternary form unboxes and reboxes on every
                // iteration of a loop that runs once per hour per series.
                Double carried =
                    values.isEmpty() ? Double.valueOf(0) : values.get(values.size() - 1);
                values.add(carried);
              }
              values.add((double) transitionCounts.getOrDefault(pair, 0L));
            });
      }
    }

    List<SeriesData> series = new ArrayList<>();
    stateValues.forEach(
        (state, values) ->
            series.add(
                new SeriesData(
                    Map.of(
                        "__name__",
                        stateSeries,
                        "job",
                        JOB,
                        "vehicle_id",
                        vehicleId,
                        "state",
                        state),
                    values,
                    timestamps)));
    series.add(
        new SeriesData(
            Map.of("__name__", batterySeries, "job", JOB, "vehicle_id", vehicleId),
            battery,
            timestamps));
    transitionValues.forEach(
        (pair, values) -> {
          String[] parts = pair.split(">");
          series.add(
              new SeriesData(
                  Map.of(
                      "__name__",
                      transitionsSeries,
                      "job",
                      JOB,
                      "vehicle_id",
                      vehicleId,
                      "from",
                      parts[0],
                      "to",
                      parts[1],
                      "category",
                      parts[2]),
                  values,
                  transitionTimestamps));
        });
    return series;
  }

  /**
   * The fault category for one transition. Only a transition into or out of ERROR carries a real
   * category — everything else (IDLE&lt;-&gt;BUSY and so on) is not a fault at all. Every fault
   * episode picks one category and keeps it for both its entry and its recovery transition, cycled
   * deterministically by episode number so a year of history exercises every category in {@link
   * #faultCategories} rather than always the first one.
   */
  private String categoryFor(String from, String to, int tick) {
    if (!"ERROR".equals(from) && !"ERROR".equals(to)) {
      return NON_FAULT_CATEGORY;
    }
    int episode = tick / FAULT_PERIOD_TICKS;
    return faultCategories.get(Math.floorMod(episode, faultCategories.size()));
  }

  /**
   * Which state a rover is in at a given moment.
   *
   * <p>Deterministic by design: derived from the vehicle index and the tick, never from a random
   * source, so the same demo replays identically.
   */
  private String stateAt(int index, long timestampMs, int tick) {
    // One rover is parked for the whole week, matching its seeded status. Without a permanently
    // offline rover, "rovers activos" would be a flat line at the fleet size.
    if (index == 4) {
      return "OFFLINE";
    }
    // One rover fails on a cycle, so mean time between failures and the Pareto have something
    // real to count rather than a single bar.
    if (index == 5 && tick % FAULT_PERIOD_TICKS < FAULT_LENGTH_TICKS) {
      return "ERROR";
    }

    int hour = ZonedDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), LOCAL).getHour();
    boolean onShift = hour >= SHIFT_START_HOUR && hour < SHIFT_END_HOUR;
    if (!onShift) {
      return "IDLE";
    }
    // A rover alternates between carrying a load and waiting for the next one; the offset by
    // index keeps the fleet from moving in lockstep, which would look obviously fake.
    return ((tick + index * 3) % 7) < 5 ? "BUSY" : "IDLE";
  }

  private double chargeAfter(double charge, String state) {
    // Deltas are per-tick, not per-hour: scaled by 6x from the original 5-minute-resolution
    // values to hold the same %/hour drain and charge rate now that each tick spans 30 minutes.
    double next =
        switch (state) {
          case "BUSY" -> charge - 2.1;
          case "IDLE" -> charge + 3.6;
          case "ERROR" -> charge - 0.6;
          default -> 0;
        };
    return Math.max(3, Math.min(100, next));
  }
}
