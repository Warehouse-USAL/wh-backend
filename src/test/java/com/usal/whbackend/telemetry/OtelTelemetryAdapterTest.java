package com.usal.whbackend.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGauge;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OtelTelemetryAdapterTest {

  private DoubleGauge gauge;
  private LongCounter counter;
  private OtelTelemetryAdapter adapter;

  private static final FleetStateSource EMPTY_FLEET =
      new FleetStateSource() {
        @Override
        public java.util.List<VehicleState> currentFleet() {
          return java.util.List.of();
        }

        @Override
        public java.util.List<String> knownStates() {
          return java.util.List.of();
        }
      };

  @BeforeEach
  void setUp() {
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    gauge = mock(DoubleGauge.class);
    counter = mock(LongCounter.class);
    when(meter.gaugeBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(gauge);
    when(meter.counterBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(counter);
    adapter = new OtelTelemetryAdapter(meter, EMPTY_FLEET);
  }

  @Test
  void recordsBatteryWithVehicleIdAttribute() {
    adapter.recordVehicleSample(new VehicleSample("VHC-001", 79));

    verify(gauge).set(eq(79.0), eq(Attributes.of(OtelTelemetryAdapter.VEHICLE_ID, "VHC-001")));
  }

  @Test
  void swallowsExporterFailureSoTelemetryNeverBreaksTheCaller() {
    org.mockito.Mockito.doThrow(new IllegalStateException("collector down"))
        .when(gauge)
        .set(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());

    assertThatCode(() -> adapter.recordVehicleSample(new VehicleSample("VHC-001", 50)))
        .doesNotThrowAnyException();
  }

  @Test
  void ignoresSamplesWithNoVehicleId() {
    adapter.recordVehicleSample(null);
    adapter.recordVehicleSample(new VehicleSample(null, 50));
    adapter.recordVehicleSample(new VehicleSample("  ", 50));

    verify(gauge, never())
        .set(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void recordsATransitionWithBothStatesAndTheCategory() {
    adapter.recordStatusTransition(
        new VehicleStatusChange("VHC-001", "BUSY", "ERROR", VehicleStatusChange.UNCATEGORIZED));

    verify(counter)
        .add(
            eq(1L),
            eq(
                Attributes.builder()
                    .put(OtelTelemetryAdapter.VEHICLE_ID, "VHC-001")
                    .put(OtelTelemetryAdapter.FROM, "BUSY")
                    .put(OtelTelemetryAdapter.TO, "ERROR")
                    .put(OtelTelemetryAdapter.CATEGORY, VehicleStatusChange.UNCATEGORIZED)
                    .build()));
  }

  @Test
  void substitutesALabelRatherThanLettingTheSdkDropIt() {
    adapter.recordStatusTransition(new VehicleStatusChange("VHC-001", null, "ERROR", null));

    // A null label would be dropped, quietly producing a series with different identity from
    // every other transition — it would not show up in a `by (from)` grouping at all.
    verify(counter)
        .add(
            eq(1L),
            eq(
                Attributes.builder()
                    .put(OtelTelemetryAdapter.VEHICLE_ID, "VHC-001")
                    .put(OtelTelemetryAdapter.FROM, "UNKNOWN")
                    .put(OtelTelemetryAdapter.TO, "ERROR")
                    .put(OtelTelemetryAdapter.CATEGORY, "UNKNOWN")
                    .build()));
  }

  @Test
  void ignoresTransitionsWithNoVehicleId() {
    adapter.recordStatusTransition(null);
    adapter.recordStatusTransition(new VehicleStatusChange(null, "IDLE", "BUSY", "X"));
    adapter.recordStatusTransition(new VehicleStatusChange(" ", "IDLE", "BUSY", "X"));

    verify(counter, never()).add(org.mockito.ArgumentMatchers.anyLong(), any());
  }

  @Test
  void swallowsCounterFailureSoTelemetryNeverBreaksTheCaller() {
    org.mockito.Mockito.doThrow(new IllegalStateException("collector down"))
        .when(counter)
        .add(org.mockito.ArgumentMatchers.anyLong(), any());

    assertThatCode(
            () -> adapter.recordStatusTransition(new VehicleStatusChange("V", "IDLE", "BUSY", "X")))
        .doesNotThrowAnyException();
  }

  @Test
  void observesEveryStateForEveryVehicleSoAbsentSeriesNeverLinger() {
    FleetStateSource fleet =
        new FleetStateSource() {
          @Override
          public List<VehicleState> currentFleet() {
            return List.of(
                new VehicleState("VHC-001", "BUSY"), new VehicleState("VHC-002", "IDLE"));
          }

          @Override
          public List<String> knownStates() {
            return List.of("IDLE", "BUSY");
          }
        };

    // Two vehicles times two states: each vehicle reports 1 for the state it is in and an
    // explicit 0 for the other, so no series simply stops being published.
    ObservableDoubleMeasurementSpy spy = new ObservableDoubleMeasurementSpy();
    Meter meter = mock(Meter.class, RETURNS_DEEP_STUBS);
    when(meter.gaugeBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(mock(DoubleGauge.class));
    when(meter.counterBuilder(anyString()).setUnit(anyString()).setDescription(anyString()).build())
        .thenReturn(mock(LongCounter.class));
    when(meter
            .gaugeBuilder(eq(OtelTelemetryAdapter.STATE_METRIC))
            .setUnit(anyString())
            .setDescription(anyString())
            .buildWithCallback(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              java.util.function.Consumer<io.opentelemetry.api.metrics.ObservableDoubleMeasurement>
                  callback = invocation.getArgument(0);
              callback.accept(spy);
              return null;
            });

    new OtelTelemetryAdapter(meter, fleet);

    assertThat(spy.recorded)
        .containsExactlyInAnyOrder(
            "VHC-001/IDLE=0.0", "VHC-001/BUSY=1.0", "VHC-002/IDLE=1.0", "VHC-002/BUSY=0.0");
  }

  /** Captures what the observable gauge publishes, keyed vehicle/state. */
  private static final class ObservableDoubleMeasurementSpy
      implements io.opentelemetry.api.metrics.ObservableDoubleMeasurement {
    private final List<String> recorded = new java.util.ArrayList<>();

    @Override
    public void record(double value) {
      recorded.add("?=" + value);
    }

    @Override
    public void record(double value, Attributes attributes) {
      recorded.add(
          attributes.get(OtelTelemetryAdapter.VEHICLE_ID)
              + "/"
              + attributes.get(OtelTelemetryAdapter.STATE)
              + "="
              + value);
    }
  }

  @Test
  void noOpAdapterDiscardsSamplesSilently() {
    assertThatCode(() -> new NoOpTelemetryAdapter().recordVehicleSample(new VehicleSample("V", 1)))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                new NoOpTelemetryAdapter()
                    .recordStatusTransition(new VehicleStatusChange("V", "IDLE", "BUSY", "X")))
        .doesNotThrowAnyException();
  }
}
